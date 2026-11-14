#!/usr/bin/env bash
set -euo pipefail

# Hardware profile: four RTX 5080 GPUs, JDK 21, Maven, CMake and CUDA.
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$project_root"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/usr/local/cuda/bin:$PATH"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Xmx100g -XX:+UseG1GC"
cpu_workers=$(getconf _NPROCESSORS_ONLN)
producers=$((cpu_workers + 32 + 96))
mkdir -p logs

cmake -S emitter/src/native -B emitter/target/native \
    -DCMAKE_BUILD_TYPE=Release -DTD20_CUDA_ARCHITECTURES=120-real \
    >> logs/emission.log 2>&1
./tools/td20 build-gpu >> logs/emission.log 2>&1

# 32 active GPU searches and 96 pending searches, plus every available CPU.
cpu_prefix_args=()
if [[ -n ${TD20_CPU_PREFIX_DEPTH:-} ]]; then
    cpu_prefix_args+=("-Dtd20.cpuPrefixDepth=$TD20_CPU_PREFIX_DEPTH")
fi
nohup ./tools/td20 emit \
    -Dtd20.gpuDevices=0,1,2,3 \
    -Dtd20.gpuSlots=8 \
    -Dtd20.gpuBudgetMB=1792 \
    -Dtd20.gpuQueueCapacity=64 \
    -Dtd20.twistedBraceletWorkers="$producers" \
    "${cpu_prefix_args[@]}" \
    -Dtd20.cpuPrefixMaxTriples=8192 \
    -Dtd20.cpuPrefixMillis="${TD20_CPU_PREFIX_MILLIS:-10}" \
    -Dtd20.certificateQueueCapacity=1048576 \
    -Dtd20.certificateLookupBatchSize=262144 \
    -Dtd20.reconstructionBufferKeys=1048576 \
    -Dtd20.certificateFlushSeconds=5 \
    --phase=all >> logs/emission.log 2>&1 < /dev/null &
printf '%s\n' "$!" > logs/emission.pid
printf 'Emission launched (PID %s).\n' "$!"
printf 'Follow: tail -F %s/logs/emission.log\n' "$project_root"
