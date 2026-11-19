#!/usr/bin/env bash
# Instance 61025877: RTX 4080 Super and /workspace volume.
td20_4080s_main() {
    set -euo pipefail
    local module=$1 action=run resume= option phase=all
    shift
    local project_root default_base default_database architecture
    local max_heap=320 max_writers=16 initial_heap=4 max_gc=16 max_concurrent_gc=4
    local default_slots=16 default_budget=768 default_gpu_queue=64
    local default_buffer=4194304 default_queue=64 default_certificate_queue=262144
    local default_lookup=32768 default_pipelines=4
    local preparation_workers=4 prepared_batches=16 prefetch_batches=32 seen_keys=262144
    project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    default_base=/workspace/td20-runtime
    default_database=/workspace/td20-certificates
    architecture=${TD20_CUDA_ARCHITECTURE:-89}
    [[ $module == emitter || $module == checker ]] || return 2
    for option in "$@"; do
        case "$option" in
            --build) action=build ;;
            --dry-run) action=dry-run ;;
            --resume-work=*) resume=${option#*=} ;;
            --help|-h)
                printf '%s\n' 'Usage: tools/run-4080s-emitter.sh [--build|--dry-run] [--resume-work=PATH]'
                printf '%s\n' "Default: full foreground run with output also saved under $default_base/logs." \
                    'Emitter resume requires a compatible sealed checkpoint; checker always starts fresh.'
                return ;;
            *) printf 'Unknown option: %s\n' "$option" >&2; return 2 ;;
        esac
    done
    if [[ $module == checker && -n $resume ]]; then
        printf '%s\n' 'The independent checker requires fresh work; resume is unsupported.' >&2
        return 2
    fi
    local base database native_dir
    base=${TD20_RUN_ROOT:-$default_base}
    database=${TD20_CERTIFICATE_DB:-$default_database}
    native_dir=$project_root/emitter/target/native-4080s
    cd -- "$project_root"

    # Respect affinity, CPU quota and memory limits on the executing host.
    local detected_cpu detected_memory
    read -r detected_cpu detected_memory < <(python3 - <<'PY'
import os, pathlib
cpu = len(os.sched_getaffinity(0))
memory = int(next(l.split()[1] for l in pathlib.Path('/proc/meminfo').read_text().splitlines()
                  if l.startswith('MemTotal:'))) * 1024
for path in ('/sys/fs/cgroup/cpu.max',):
    try:
        quota, period = pathlib.Path(path).read_text().split()
        if quota != 'max': cpu = min(cpu, max(1, int(quota) // int(period)))
    except (OSError, ValueError): pass
try:
    quota = int(pathlib.Path('/sys/fs/cgroup/cpu/cpu.cfs_quota_us').read_text())
    period = int(pathlib.Path('/sys/fs/cgroup/cpu/cpu.cfs_period_us').read_text())
    if quota > 0: cpu = min(cpu, max(1, quota // period))
except (OSError, ValueError): pass
for path in ('/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory/memory.limit_in_bytes'):
    try:
        value = pathlib.Path(path).read_text().strip()
        if value != 'max': memory = min(memory, int(value))
    except (OSError, ValueError): pass
print(cpu, memory // (1024**3))
PY
    )
    local cpu=${TD20_CPU_WORKERS:-$detected_cpu}
    local default_heap=$((detected_memory * 5 / 8))
    (( default_heap > max_heap )) && default_heap=$max_heap
    local heap=${TD20_HEAP_GIB:-$default_heap}
    local writers=${TD20_WRITERS:-$((cpu < max_writers ? cpu : max_writers))}
    local slots=${TD20_GPU_SLOTS:-$default_slots} budget=${TD20_GPU_BUDGET_MB:-$default_budget}
    [[ $slots =~ ^[1-9][0-9]*$ ]] || return 2
    if [[ -z ${TD20_GPU_BUDGET_MB:-} && $action != build ]]; then
        local available_gpu
        available_gpu=$(nvidia-smi --id=0 --query-gpu=memory.free --format=csv,noheader,nounits)
        [[ $available_gpu =~ ^[0-9]+$ ]] || return 2
        # Use most free VRAM for useful search queues, leaving native cache headroom.
        budget=$(((available_gpu - 2048) * 9 / 10 / slots - 12))
        (( budget > 0 )) || { printf '%s\n' 'Insufficient GPU memory.' >&2; return 2; }
    fi
    local gpu_queue=${TD20_GPU_QUEUE_CAPACITY:-$default_gpu_queue}
    local callers=$((2 * slots + gpu_queue))
    local initial=$((heap < initial_heap ? heap : initial_heap))
    local gc_threads=$((cpu < max_gc ? cpu : max_gc))
    local concurrent_gc=$(((cpu + 3) / 4))
    (( concurrent_gc > max_concurrent_gc )) && concurrent_gc=$max_concurrent_gc
    local value
    for value in "$cpu" "$heap" "$writers" "$slots" "$budget" "$architecture"; do
        [[ $value =~ ^[1-9][0-9]*$ ]] || { printf 'Invalid positive setting: %s\n' "$value" >&2; return 2; }
    done
    [[ $gpu_queue =~ ^[0-9]+$ ]] || return 2
    (( heap + 8 <= detected_memory )) || { printf '%s\n' 'Heap must leave at least 8 GiB outside Java.' >&2; return 2; }
    [[ $base = /* && $database = /* ]] || { printf '%s\n' 'Run root and database paths must be absolute.' >&2; return 2; }
    [[ $base != *[[:space:]]* && $database != *[[:space:]]* ]] || {
        printf '%s\n' 'JShell launcher option paths must not contain whitespace.' >&2; return 2;
    }

    local stamp work log settings
    stamp=$(date -u +%Y%m%dT%H%M%SZ)-$$
    work=$base/work/$module-$stamp
    log=$base/logs/$module-$stamp.log
    settings=$base/logs/$module-$stamp.json
    local -a jvm=(
        -J-Xmx512m "-R-Xms${initial}g" "-R-Xmx${heap}g"
        -R-XX:+UseG1GC -R-XX:+ParallelRefProcEnabled -R-XX:MaxGCPauseMillis=200
        "-R-XX:ActiveProcessorCount=$cpu" "-R-XX:ParallelGCThreads=$gc_threads"
        "-R-XX:ConcGCThreads=$concurrent_gc" -R-Duser.timezone=Europe/Lisbon
        "-R-Djava.io.tmpdir=$base/tmp" "-R-Dtd20.twistedBraceletWorkers=$cpu"
        "-R-Dtd20.certificateDb=$database" -R-Dtd20.dbWriteBatchEntries=524288
        "-R-Dtd20.reconstructionWriters=$writers"
        "-R-Dtd20.reconstructionBufferKeys=${TD20_BUFFER_KEYS:-$default_buffer}"
        "-R-Dtd20.reconstructionQueueBatches=${TD20_QUEUE_BATCHES:-$default_queue}"
        "-R-Dtd20.reconstructionGeneratedBatchKeys=${TD20_GENERATED_BATCH_KEYS:-8192}"
        "-R-Dtd20.reconstructionScanBatchKeys=${TD20_SCAN_BATCH_KEYS:-4096}"
    )
    local -a invocation=(./tools/td20)
    if [[ $module == emitter ]]; then
        jvm+=("-R-Djava.library.path=$native_dir" -R-Dtd20.gpuDevices=0
            "-R-Dtd20.gpuSlots=$slots" "-R-Dtd20.gpuBudgetMB=$budget"
            "-R-Dtd20.gpuQueueCapacity=$gpu_queue" -R-Dtd20.gpuStatsPeriodSeconds=10
            "-R-Dtd20.reconstructionGpuWorkers=$callers" "-R-Dtd20.reconstructionInverseWorkers=$cpu"
            -R-Dtd20.cpuPrefixMaxTriples=8192 -R-Dtd20.reconstructionCpuPrefixMillis=10
            -R-Dtd20.reconstructionGpuMillis=0 -R-Dtd20.cpuStatsPeriodSeconds=10
            "-R-Dtd20.certificateQueueCapacity=$default_certificate_queue" -R-Dtd20.certificateFlushSeconds=5
            "-R-Dtd20.certificateWriters=${TD20_CERTIFICATE_WRITERS:-$default_slots}"
            "-R-Dtd20.certificateLookupBatchSize=${TD20_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd20.rootPrefetchWorkers=$callers" "-R-Dtd20.rootPrefetchPreparationWorkers=${TD20_PREPARATION_WORKERS:-$(((cpu + 3) / 4))}"
            "-R-Dtd20.rootPrefetchPreparedQueueBatches=$prepared_batches" "-R-Dtd20.rootPrefetchQueueBatches=$prefetch_batches"
            "-R-Dtd20.rootPrefetchSeenKeys=$seen_keys" -R-Dtd20.rootPrefetchGpuMillis=100)
        if [[ -n $resume ]]; then phase=avoidance; invocation+=(emit --phase=avoidance "--resume-work=$resume");
        else invocation+=(emit --phase=all); fi
    else
        jvm+=("-R-Dtd20.checkerWorkers=$cpu" -R-Dtd20.checkerStatsPeriodSeconds=10
            "-R-Dtd20.certificateLookupBatchSize=${TD20_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd20.certificateLookupPipelines=${TD20_LOOKUP_PIPELINES:-$default_pipelines}")
        invocation+=(verify --phase=all)
    fi
    invocation+=("--database=$database" "--work=$work")
    printf -v TD20_JSHELL_OPTIONS '%s ' "${jvm[@]}"
    export TD20_JSHELL_OPTIONS
    export TD20_LAUNCH_TMPDIR="$base/tmp"
    if [[ $action == dry-run ]]; then
        printf 'profile=4080s module=%s cpuWorkers=%s heapGiB=%s writers=%s\n' "$module" "$cpu" "$heap" "$writers"
        [[ $module != emitter ]] || printf 'gpuSlots=%s queueMiBPerSlot=%s aggregateQueueGiB=%s GPUCallers=%s\n' \
            "$slots" "$budget" "$((slots * budget / 1024))" "$callers"
        [[ $module != emitter ]] || printf 'nativeArchitecture=sm_%s nativeBuild=%s\n' "$architecture" "$native_dir"
        printf 'log=%s\nsettings=%s\n' "$log" "$settings"
        printf 'TD20_JSHELL_OPTIONS=%q\n' "$TD20_JSHELL_OPTIONS"
        printf '%q ' "${invocation[@]}"; printf '\n'
        return
    fi

    if [[ -z ${JAVA_HOME:-} && -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    fi
    [[ -z ${JAVA_HOME:-} ]] || export PATH="$JAVA_HOME/bin:$PATH"
    local java_version
    java_version=$(java -version 2>&1)
    [[ $java_version =~ version\ \"21[.\"] ]] || {
        printf '%s\n' 'Set JAVA_HOME to JDK 21 (including jshell and javac).' >&2; return 2;
    }
    if [[ $base == /workspace/* || $database == /workspace/* ]]; then
        local workspace_kib
        workspace_kib=$(df -Pk /workspace | awk 'NR==2 {print $2}')
        (( workspace_kib >= 128 * 1024 * 1024 )) || {
            printf '%s\n' 'Attach the large /workspace volume before building or running.' >&2; return 2;
        }
    fi
    mkdir -p "$base/logs" "$base/work" "$base/tmp"
    exec 9>"$base/run.lock"
    flock -n 9 || { printf '%s\n' 'Another emitter/checker run or build holds the workspace lock.' >&2; return 2; }
    if [[ $action == build ]]; then
        mvn -q -pl "$module" -am compile dependency:build-classpath -Dmdep.outputFile=target/jshell-classpath
        if [[ $module == emitter ]]; then
            command -v nvcc >/dev/null
            nvcc --list-gpu-code | grep -Fx "sm_$architecture" >/dev/null || {
                printf 'The installed CUDA toolkit does not support sm_%s.\n' "$architecture" >&2; return 2;
            }
            local host_compiler=${TD20_CUDA_HOST_COMPILER:-}
            if [[ -z $host_compiler ]] && command -v g++-12 >/dev/null; then
                host_compiler=$(command -v g++-12)
            fi
            local -a compiler_options=()
            [[ -z $host_compiler ]] || compiler_options+=("-DCMAKE_CUDA_HOST_COMPILER=$host_compiler")
            cmake -S emitter/src/native -B "$native_dir" -DCMAKE_BUILD_TYPE=Release \
                "-DTD20_CUDA_ARCHITECTURES=$architecture-real;$architecture-virtual" \
                "-DTD20_BUILD_NATIVE_TESTS=${TD20_BUILD_NATIVE_TESTS:-OFF}" "${compiler_options[@]}"
            cmake --build "$native_dir" --parallel "$cpu"
        fi
        return
    fi
    test -f "$module/target/jshell-classpath"
    if [[ $module == emitter ]]; then
        test -f "$native_dir/libtd20_jni.so"
        nvidia-smi --query-gpu=name,memory.free,memory.total,driver_version --format=csv
        # Native preflight additionally checks exact queue, cache and metadata bytes.
        local free_gpu
        free_gpu=$(nvidia-smi --id=0 --query-gpu=memory.free --format=csv,noheader,nounits)
        (( free_gpu >= slots * (budget + 12) + 1024 )) || {
            printf '%s\n' 'Insufficient free VRAM for queues, state caches and reserve.' >&2; return 2;
        }
    else
        test -f "$database/CURRENT"
    fi
    # The run lock is held: old interrupted outputs are disposable, while the
    # selected complete source wave and certificate databases remain protected.
    local -a cleanup=(python3 tools/local-work-cleanup.py --base "$base" --database "$database" --lock-fd=9)
    [[ -z $resume ]] || cleanup+=("--resume-work=$resume")
    "${cleanup[@]}"
    local free_disk
    free_disk=$(df -Pk "$base" | awk 'NR==2 {print $4}')
    (( free_disk >= ${TD20_MIN_FREE_GIB:-64} * 1024 * 1024 )) || {
        printf '%s\n' 'Insufficient free workspace storage after abandoned-work cleanup.' >&2; return 2;
    }
    touch "$log"
    ln -sfn "$(basename -- "$log")" "$base/logs/$module-latest.log"
    printf '%s\n' "$$" > "$base/logs/$module.pid"
    python3 - "$module" "$work" "$database" "$settings" "$$" "$phase" 4080s "$native_dir" <<'PY'
import datetime, hashlib, json, os, pathlib, subprocess, sys
settings = dict(format='td20-'+sys.argv[7]+'-run-v1', profile=sys.argv[7], module=sys.argv[1], work=sys.argv[2],
    database=sys.argv[3], launcherPid=int(sys.argv[5]), phase=sys.argv[6], status='starting',
    startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    revision=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
    jshellOptions=os.environ['TD20_JSHELL_OPTIONS'].split(), walEnabled=False,
    cpuAffinity=sorted(os.sched_getaffinity(0)),
    javaVersion=subprocess.run(['java','-version'], capture_output=True, text=True, check=True).stderr,
    platform=list(os.uname()))
classes=pathlib.Path(sys.argv[1])/'target/classes'
settings['javaClassSha256']={str(p.relative_to(classes)):hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted(classes.rglob('*.class'))}
settings['sourceStatus']=subprocess.check_output(['git','status','--porcelain']).decode()
if sys.argv[1]=='emitter':
    native=pathlib.Path(sys.argv[8])/'libtd20_jni.so'
    settings['nativeSha256']=hashlib.sha256(native.read_bytes()).hexdigest()
pathlib.Path(sys.argv[4]).write_text(json.dumps(settings, indent=2)+'\n')
PY
    printf 'INFO %s starting; work=%s; log=%s; WAL=disabled\n' "$module" "$work" "$log" | tee -a "$log"
    local result=0 marker
    local -a pipeline_codes
    if "${invocation[@]}" 2>&1 | tee -a "$log"; then result=0;
    else
        pipeline_codes=("${PIPESTATUS[@]}")
        result=${pipeline_codes[0]}
        (( result != 0 )) || result=1
    fi
    if [[ $module == checker ]]; then marker='All regenerated obligations in the complete joint-selection scope are verified.';
    else marker='Requested emission scope completed:'; fi
    # JShell termination alone is not a successful verification or emission.
    if (( result == 0 )) && ! grep -Fq "$marker" "$log"; then result=1; fi
    python3 - "$settings" "$result" <<'PY'
import datetime, json, pathlib, sys
p=pathlib.Path(sys.argv[1]); settings=json.loads(p.read_text())
settings.update(exitCode=int(sys.argv[2]), status='complete' if sys.argv[2]=='0' else 'failed',
    finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
p.write_text(json.dumps(settings, indent=2)+'\n')
PY
    printf 'INFO %s exited: code=%s\n' "$module" "$result" | tee -a "$log"
    return "$result"
}

source "$HOME/.bashrc"
td20_4080s_main emitter "$@"
