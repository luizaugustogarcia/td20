#!/usr/bin/env bash
# Local launch configuration; neither module depends on the other artifact.
td20_local_main() {
    set -euo pipefail
    local module=$1 action=run resume= option phase=all work_override= structural=false
    shift
    local project_root default_base default_database architecture
    local max_heap=16 max_writers=8 initial_heap=2 max_gc=8 max_concurrent_gc=2
    local default_slots=8 default_budget=768 default_gpu_queue=16
    local default_buffer=4194304 default_queue=512 default_certificate_queue=262144
    local default_lookup=32768
    local preparation_workers=4 prepared_batches=16 prefetch_batches=32 seen_keys=262144
    project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    source "$project_root/tools/launcher-options.sh"
    default_base=$project_root/local-runtime
    default_database=$project_root/certificates
    [[ $module == emitter || $module == checker ]] || return 2
    local -a expanded_arguments=() runtime_arguments=() command_jvm=() java_arguments=()
    td20_parse_words expanded_arguments arguments "$@"
    for option in "${expanded_arguments[@]}"; do
        case "$option" in
            --build) action=build ;;
            --dry-run) action=dry-run ;;
            --resume-work=*) resume=${option#*=} ;;
            --cpu-workers=*) export TD20_CPU_WORKERS=${option#*=} ;;
            --heap-gib=*) export TD20_HEAP_GIB=${option#*=} ;;
            --run-root=*) export TD20_RUN_ROOT=${option#*=} ;;
            --database=*) export TD20_CERTIFICATE_DB=${option#*=} ;;
            --work=*) work_override=${option#*=} ;;
            --phase=*) phase=${option#*=} ;;
            --partitions=*|--endpoints=*|--paper=*) java_arguments+=("$option") ;;
            --structural-only) structural=true; java_arguments+=("$option") ;;
            --setting=*)
                local assignment=${option#*=}
                [[ $assignment =~ ^TD20_[A-Z0-9_]+= ]] || {
                    printf 'Expected --setting=TD20_NAME=value: %s\n' "$option" >&2; return 2;
                }
                export "$assignment" ;;
            -D*|-R*|-J*|--jvm-option=*) runtime_arguments+=("$option") ;;
            --help|-h)
                printf '%s\n' 'Usage: tools/run-local-{emitter,checker}.sh [--build|--dry-run] [execution options]'
                printf '%s\n' "Default: full foreground run with output also saved under $default_base/logs." \
                    'Emitter resume uses --resume-work=PATH; checker always starts fresh.' \
                    'Resource options: --cpu-workers=N --heap-gib=N --run-root=PATH --database=PATH' \
                    'Computation options: --phase=PHASE --partitions=FIRST-LAST --endpoints=FIRST-LAST --paper=PATH --work=PATH' \
                    'Set any TD20 launcher setting with --setting=TD20_NAME=value.' \
                    'Application options: -Dtd20.NAME=value. JVM options: -R-Xmx24g or --jvm-option=-XX:OPTION.' \
                    'Options also accept -Dexec.args or -Dtd20.args with shell-style quoting.' \
                    'Command-line JVM options override environment and profile defaults.'
                return ;;
            *) printf 'Unknown option: %s\n' "$option" >&2; return 2 ;;
        esac
    done
    case "$phase" in
        roots|endpoints|avoidance|all) ;;
        *) printf 'Invalid phase: %s\n' "$phase" >&2; return 2 ;;
    esac
    td20_collect_jvm_options command_jvm java_arguments "${runtime_arguments[@]}"
    architecture=${TD20_CUDA_ARCHITECTURE:-89}
    if [[ $module == checker && -n $resume ]]; then
        printf '%s\n' 'The independent checker requires fresh work; resume is unsupported.' >&2
        return 2
    fi
    local base database native_dir
    base=${TD20_RUN_ROOT:-$default_base}
    database=${TD20_CERTIFICATE_DB:-$default_database}
    native_dir=$project_root/emitter/target/native
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
    td20_integer_setting cpu "$cpu" 1
    if [[ $module == checker ]]; then
        default_lookup=32768
    fi
    local default_heap=$((detected_memory * 5 / 8))
    (( default_heap > max_heap )) && default_heap=$max_heap
    local heap=${TD20_HEAP_GIB:-$default_heap}
    td20_integer_setting heap "$heap" 1
    local slots=${TD20_GPU_SLOTS:-$default_slots} budget=${TD20_GPU_BUDGET_MB:-$default_budget}
    local gpu_queue=${TD20_GPU_QUEUE_CAPACITY:-$default_gpu_queue}
    local resources heap_bytes initial_bytes heap_display gpu_devices device_count aggregate_queue_gib
    resources=$(td20_resource_options "$heap" "$initial_heap" "$slots" "$budget" "$gpu_queue" "${command_jvm[@]}") || return 2
    read -r heap_bytes initial_bytes heap_display slots budget gpu_queue gpu_devices device_count aggregate_queue_gib <<< "$resources"
    local heap_flag=$heap_bytes
    if (( heap_bytes == 0 )); then heap_flag=${heap}g; fi
    local sizing_heap_mb=$((heap_bytes > 0 ? heap_bytes / 1048576 : heap * 1024))
    local checker_buffer_mb=$((sizing_heap_mb / 3)) checker_cache_mb=$((sizing_heap_mb / 4))
    (( checker_buffer_mb > 4096 )) && checker_buffer_mb=4096
    (( checker_buffer_mb < 128 )) && checker_buffer_mb=128
    (( checker_cache_mb > 4096 )) && checker_cache_mb=4096
    (( checker_cache_mb < 64 )) && checker_cache_mb=64
    local writers=${TD20_WRITERS:-$((cpu < max_writers ? cpu : max_writers))}
    td20_integer_setting writers "$writers" 1
    td20_integer_setting slots "$slots" 1
    td20_integer_setting budget "$budget" 1
    td20_integer_setting gpu_queue "$gpu_queue" 0
    td20_integer_setting architecture "$architecture" 1
    local callers=$((2 * slots * device_count + gpu_queue))
    local gc_threads=$((cpu < max_gc ? cpu : max_gc))
    local concurrent_gc=$(((cpu + 3) / 4))
    (( concurrent_gc > max_concurrent_gc )) && concurrent_gc=$max_concurrent_gc
    (( heap_bytes == 0 || heap_bytes <= (detected_memory - 8) * 1073741824 )) || {
        printf '%s\n' 'Heap must leave at least 8 GiB outside Java.' >&2; return 2;
    }
    [[ $base = /* && $database = /* ]] || { printf '%s\n' 'Run root and database paths must be absolute.' >&2; return 2; }
    local stamp work log settings
    stamp=$(date -u +%Y%m%dT%H%M%SZ)-$$
    work=${work_override:-$base/work/$module-$stamp}
    [[ $work = /* ]] || { printf '%s\n' 'Work paths must be absolute.' >&2; return 2; }
    if [[ $module == checker && $structural == false && ( -e $work || -L $work ) ]]; then
        printf 'Checker work must be fresh: %s\n' "$work" >&2; return 2
    fi
    log=$base/logs/$module-$stamp.log
    settings=$base/logs/$module-$stamp.json
    local -a jvm=(
        -J-Xmx512m "-R-Xms${initial_bytes}" "-R-Xmx${heap_flag}"
        -R-XX:+UseG1GC -R-XX:+ParallelRefProcEnabled -R-XX:MaxGCPauseMillis=200
        "-R-XX:ActiveProcessorCount=$cpu" "-R-XX:ParallelGCThreads=$gc_threads"
        "-R-XX:ConcGCThreads=$concurrent_gc" -R-Duser.timezone=Europe/Lisbon
        "-R-Djava.io.tmpdir=$base/tmp" "-R-Dtd20.twistedBraceletWorkers=$cpu"
        "-R-Dtd20.certificateDb=$database" -R-Dtd20.dbWriteBatchEntries=524288
        "-R-Dtd20.reconstructionWriters=$writers"
        "-R-Dtd20.reconstructionBufferKeys=${TD20_BUFFER_KEYS:-$default_buffer}"
        "-R-Dtd20.reconstructionQueueBatches=${TD20_QUEUE_BATCHES:-$default_queue}"
        "-R-Dtd20.reconstructionGeneratedBatchKeys=${TD20_GENERATED_BATCH_KEYS:-65536}"
        "-R-Dtd20.reconstructionScanBatchKeys=${TD20_SCAN_BATCH_KEYS:-4096}"
    )
    td20_add_numa_option jvm
    local -a invocation=(./tools/td20)
    if [[ $module == emitter ]]; then
        jvm+=("-R-Djava.library.path=$native_dir" "-R-Dtd20.gpuDevices=$gpu_devices"
            "-R-Dtd20.gpuSlots=$slots" "-R-Dtd20.gpuBudgetMB=$budget"
            "-R-Dtd20.gpuQueueCapacity=$gpu_queue" -R-Dtd20.gpuStatsPeriodSeconds=10
            "-R-Dtd20.reconstructionGpuWorkers=$callers" "-R-Dtd20.reconstructionInverseWorkers=$cpu"
            -R-Dtd20.cpuPrefixMaxTriples=8192 -R-Dtd20.reconstructionCpuPrefixMillis=10
            -R-Dtd20.reconstructionGpuMillis=0 -R-Dtd20.cpuStatsPeriodSeconds=10
            "-R-Dtd20.certificateQueueCapacity=$default_certificate_queue" -R-Dtd20.certificateFlushSeconds=5
            "-R-Dtd20.certificateWriters=${TD20_CERTIFICATE_WRITERS:-$default_slots}"
            "-R-Dtd20.certificateLookupBatchSize=${TD20_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd20.rootPrefetchWorkers=$callers" "-R-Dtd20.rootPrefetchPreparationWorkers=$preparation_workers"
            "-R-Dtd20.rootPrefetchPreparedQueueBatches=$prepared_batches" "-R-Dtd20.rootPrefetchQueueBatches=$prefetch_batches"
            "-R-Dtd20.rootPrefetchSeenKeys=$seen_keys" -R-Dtd20.rootPrefetchGpuMillis=100)
        if [[ -n $resume ]]; then phase=avoidance; invocation+=(emit --phase=avoidance "--resume-work=$resume");
        else invocation+=(emit "--phase=$phase"); fi
    else
        local read_workers=$cpu
        local rocksdb_mb=${TD20_ROCKSDB_MEMORY_MB:-$checker_cache_mb}
        td20_integer_setting rocksdb_mb "$rocksdb_mb" 1
        local rocksdb_write_mb=${TD20_ROCKSDB_WRITE_BUFFER_MB:-$((rocksdb_mb / 8))}
        td20_integer_setting rocksdb_write_mb "$rocksdb_write_mb" 1
        jvm+=("-R-Dtd20.checkerWorkers=$cpu" -R-Dtd20.checkerStatsPeriodSeconds=10
            "-R-Dtd20.certificateLookupBatchSize=${TD20_LOOKUP_BATCH_KEYS:-$default_lookup}"
            "-R-Dtd20.certificateReadWorkers=${TD20_READ_WORKERS:-$read_workers}"
            "-R-Dtd20.certificateProducerBatchKeys=${TD20_PRODUCER_BATCH_KEYS:-4096}"
            "-R-Dtd20.certificateBufferedMB=${TD20_CERTIFICATE_BUFFER_MB:-$checker_buffer_mb}"
            "-R-Dtd20.certificateReadCacheMB=${TD20_READ_CACHE_MB:-$checker_cache_mb}"
            "-R-Dtd20.certificateMaxOpenFiles=${TD20_READ_MAX_OPEN_FILES:-512}"
            "-R-Dtd20.rocksDbMemoryMB=$rocksdb_mb"
            "-R-Dtd20.rocksDbWriteBufferMB=$rocksdb_write_mb"
            "-R-Dtd20.rocksDbStrictCapacity=${TD20_ROCKSDB_STRICT_CAPACITY:-true}"
            "-R-Dtd20.certificateCacheIndexAndFilterBlocks=${TD20_ROCKSDB_CACHE_METADATA:-true}"
            "-R-Dtd20.rocksDbFillCache=${TD20_ROCKSDB_FILL_CACHE:-true}"
            "-R-Dtd20.certificateRootFillCache=${TD20_ROOT_FILL_CACHE:-true}"
            "-R-Dtd20.certificateEndpointFillCache=${TD20_ENDPOINT_FILL_CACHE:-true}"
            "-R-Dtd20.certificateReconstructionFillCache=${TD20_RECONSTRUCTION_FILL_CACHE:-true}"
            "-R-Dtd20.rocksDbWriteStall=${TD20_ROCKSDB_WRITE_STALL:-true}"
            "-R-Dtd20.rocksDbCacheShardBits=${TD20_ROCKSDB_CACHE_SHARD_BITS:--1}"
            "-R-Dtd20.rocksDbCacheHighPriorityRatio=${TD20_ROCKSDB_CACHE_HIGH_PRIORITY_RATIO:-0.5}"
            "-R-Dtd20.rocksDbMemtableMB=${TD20_ROCKSDB_MEMTABLE_MB:-64}"
            "-R-Dtd20.rocksDbMaxWriteBuffers=${TD20_ROCKSDB_MAX_WRITE_BUFFERS:-4}"
            "-R-Dtd20.rocksDbScratchMaxOpenFiles=${TD20_ROCKSDB_SCRATCH_MAX_OPEN_FILES:-128}"
            "-R-Dtd20.checkerMemoryProgress=${TD20_MEMORY_PROGRESS:-true}")
        invocation+=(verify "--phase=$phase")
    fi
    invocation+=("--database=$database" "--work=$work" "${java_arguments[@]}")
    local -a environment_jvm=()
    td20_parse_words environment_jvm options "${TD20_JSHELL_OPTIONS:-}"
    # The resource profile determines dependent defaults and preflight checks.
    # Explicit execution-VM arguments have the final override.
    jvm=("${environment_jvm[@]}" "${jvm[@]}" "${command_jvm[@]}")
    TD20_JSHELL_OPTIONS=$(python3 -c 'import shlex, sys; print(shlex.join(sys.argv[1:]))' "${jvm[@]}")
    export TD20_JSHELL_OPTIONS
    export TD20_LAUNCH_TMPDIR="$base/tmp"
    if [[ $action == dry-run ]]; then
        printf 'profile=local module=%s cpuWorkers=%s heapGiB=%s writers=%s\n' "$module" "$cpu" "$heap_display" "$writers"
        [[ $module != emitter ]] || printf 'gpuDevices=%s gpuSlotsPerDevice=%s queueMiBPerSlot=%s aggregateQueueGiB=%s GPUCallers=%s\n' \
            "$gpu_devices" "$slots" "$budget" "$aggregate_queue_gib" "$callers"
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
        local free_gpu device
        local -a selected_devices=()
        IFS=, read -r -a selected_devices <<< "$gpu_devices"
        for device in "${selected_devices[@]}"; do
            free_gpu=$(nvidia-smi --id="$device" --query-gpu=memory.free --format=csv,noheader,nounits)
            td20_integer_setting free_gpu "$free_gpu" 0
            if ! python3 - "$free_gpu" "$slots" "$budget" <<'PY'
import sys
free, slots, budget = map(int, sys.argv[1:])
sys.exit(0 if free >= slots * (budget + 12) + 1024 else 1)
PY
            then
                printf 'Insufficient free VRAM on device %s for queues, state caches and reserve.\n' "$device" >&2
                return 2
            fi
        done
    elif [[ $structural == false ]]; then
        test -f "$database/CURRENT"
    fi
    # The run lock is held: old interrupted outputs are disposable, while the
    # selected complete source wave and certificate databases remain protected.
    local -a cleanup=(python3 tools/local-work-cleanup.py --base "$base" --database "$database" --lock-fd=9)
    [[ -z $resume ]] || cleanup+=("--resume-work=$resume")
    "${cleanup[@]}"
    local free_disk minimum_free_gib=${TD20_MIN_FREE_GIB:-32}
    td20_integer_setting minimum_free_gib "$minimum_free_gib" 0
    free_disk=$(df -Pk "$base" | awk 'NR==2 {print $4}')
    (( free_disk >= minimum_free_gib * 1024 * 1024 )) || {
        printf '%s\n' 'Insufficient free workspace storage after abandoned-work cleanup.' >&2; return 2;
    }
    touch "$log"
    ln -sfn "$(basename -- "$log")" "$base/logs/$module-latest.log"
    printf '%s\n' "$$" > "$base/logs/$module.pid"
    python3 - "$module" "$work" "$database" "$settings" "$$" "$phase" local "$native_dir" "${invocation[@]}" <<'PY'
import datetime, hashlib, json, os, pathlib, shlex, subprocess, sys
jshell_options = shlex.split(os.environ['TD20_JSHELL_OPTIONS'])
settings = dict(format='td20-'+sys.argv[7]+'-run-v1', profile=sys.argv[7], module=sys.argv[1], work=sys.argv[2],
    database=sys.argv[3], launcherPid=int(sys.argv[5]), phase=sys.argv[6], status='starting',
    startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    revision=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
    jshellOptions=jshell_options, executionArguments=sys.argv[9:], walEnabled=False,
    cpuAffinity=sorted(os.sched_getaffinity(0)),
    javaVersion=subprocess.run(['java','-version'], capture_output=True, text=True, check=True).stderr,
    platform=list(os.uname()))
settings['systemProperties'] = {}
for option in jshell_options:
    if option.startswith('-R-D'):
        name, separator, value = option[4:].partition('=')
        settings['systemProperties'][name] = value
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
    local result=0 completion_scope=requested
    local -a pipeline_codes
    if "${invocation[@]}" 2>&1 | tee -a "$log"; then result=0;
    else
        pipeline_codes=("${PIPESTATUS[@]}")
        result=${pipeline_codes[0]}
        (( result != 0 )) || result=1
    fi
    # JShell termination alone is not a successful verification or emission.
    if (( result == 0 )); then
        if [[ $module == checker ]]; then
            if grep -Fq 'All regenerated obligations in the complete joint-selection scope are verified.' "$log"; then
                completion_scope=complete
            elif grep -Fq 'Requested partial verification scope completed;' "$log"; then
                completion_scope=partial
            elif [[ $structural == true ]] && grep -Fq 'Structural inventory checks only;' "$log"; then
                completion_scope=structural
            else result=1; fi
        elif ! grep -Fq 'Requested emission scope completed:' "$log"; then
            result=1
        fi
    fi
    python3 - "$settings" "$result" "$completion_scope" <<'PY'
import datetime, json, pathlib, sys
p=pathlib.Path(sys.argv[1]); settings=json.loads(p.read_text())
scope = sys.argv[3]
status = {'partial': 'partial-complete', 'structural': 'structural-complete'}.get(scope, 'complete')
settings.update(exitCode=int(sys.argv[2]), status=status if sys.argv[2]=='0' else 'failed', completionScope=scope,
    finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
p.write_text(json.dumps(settings, indent=2)+'\n')
PY
    printf 'INFO %s exited: code=%s\n' "$module" "$result" | tee -a "$log"
    return "$result"
}
