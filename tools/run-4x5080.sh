#!/usr/bin/env bash
set -euo pipefail

# Run on the server with JDK 21, Maven, CMake >= 3.24 and CUDA 12.8–13.0 installed.
# All source, certificates, work directories and logs stay on /workspace.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/usr/local/cuda/bin:$PATH"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Xmx100g -XX:+UseG1GC -XX:ActiveProcessorCount=64"

mkdir -p /workspace
if [[ ! -d /workspace/td20/.git ]]; then
    git clone --branch td20-reduction-routing \
        https://github.com/luizaugustogarcia/td20.git /workspace/td20
fi
cd /workspace/td20
mkdir -p logs

# RTX 5080: build only the native sm_120 target.
cmake -S emitter/src/native -B emitter/target/native \
    -DCMAKE_BUILD_TYPE=Release -DTD20_CUDA_ARCHITECTURES=120-real \
    >> logs/emission.log 2>&1
./tools/td20 build-gpu >> logs/emission.log 2>&1

# 32 active searches; 14 GiB of queue storage per GPU (56 GiB total).
# Keep the remaining VRAM available for CUDA contexts and allocator overhead.
# 64 additional GPU queue entries give 96 pending searches in total.
# 192 producers cover 32 active + 96 pending searches and 64 CPU workers.
# Every root, endpoint, and reconstructed class gets at most 10 ms before the full
# original obligation falls through to CUDA.
# Reuse certificates on subsequent runs; no existing data is deleted.
cpu_prefix_args=()
if [[ -n ${TD20_CPU_PREFIX_DEPTH:-} ]]; then
    cpu_prefix_args+=("-Dtd20.cpuPrefixDepth=$TD20_CPU_PREFIX_DEPTH")
fi
nohup ./tools/td20 emit -B -Dstyle.color=never \
    -Dtd20.gpuDevices=0,1,2,3 \
    -Dtd20.gpuSlots=8 \
    -Dtd20.gpuBudgetMB=1792 \
    -Dtd20.gpuQueueCapacity=64 \
    -Dtd20.twistedBraceletWorkers=192 \
    "${cpu_prefix_args[@]}" \
    -Dtd20.cpuPrefixMaxTriples=8192 \
    -Dtd20.cpuPrefixMillis="${TD20_CPU_PREFIX_MILLIS:-10}" \
    -Dtd20.certificateQueueCapacity=1048576 \
    -Dtd20.certificateLookupBatchSize=262144 \
    -Dtd20.reconstructionBufferKeys=1048576 \
    -Dtd20.certificateFlushSeconds=5 \
    -Dtd20.certificateDb=/workspace/td20/certificates \
    -Dtd20.args=--phase=all \
    >> logs/emission.log 2>&1 < /dev/null &

printf '%s\n' "$!" > logs/emission.pid
printf 'Emission launched (PID %s).\n' "$!"
printf 'Follow: tail -F /workspace/td20/logs/emission.log\n'
