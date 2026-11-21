#!/usr/bin/env bash
# Instance 61821972, host 51010, machine 153507.
# Dual-socket EPYC 9654, 384 allocated CPU threads and about 257 GB RAM.
source "$HOME/.bashrc"
source "$(dirname -- "${BASH_SOURCE[0]}")/local-profile.sh"

# CPU and reader counts follow the current allocation. Caller arguments override
# these resource-based defaults through the shared execution-argument parser.
td20_local_main checker \
    --setting=TD20_CPU_WORKERS= \
    --setting=TD20_READ_WORKERS= \
    --setting=TD20_WRITERS=16 \
    --heap-gib=32 \
    -R-Xms4g \
    -R-XX:+UseNUMA \
    -R-XX:ParallelGCThreads=32 \
    -R-XX:ConcGCThreads=8 \
    -Dtd20.certificateLookupBatchSize=4096 \
    -Dtd20.certificateProducerBatchKeys=4096 \
    -Dtd20.certificateBufferedMB=4096 \
    -Dtd20.certificateReadCacheMB=131072 \
    -Dtd20.certificateMaxOpenFiles=512 \
    -Dtd20.rocksDbMemoryMB=131072 \
    -Dtd20.rocksDbWriteBufferMB=16384 \
    -Dtd20.rocksDbCacheShardBits=8 \
    -Dtd20.rocksDbStrictCapacity=true \
    -Dtd20.certificateCacheIndexAndFilterBlocks=true \
    -Dtd20.rocksDbFillCache=true \
    -Dtd20.certificateRootFillCache=true \
    -Dtd20.certificateEndpointFillCache=true \
    -Dtd20.certificateReconstructionFillCache=true \
    -Dtd20.reconstructionBufferKeys=8388608 \
    -Dtd20.reconstructionQueueBatches=128 \
    -Dtd20.reconstructionGeneratedBatchKeys=65536 \
    -Dtd20.reconstructionScanBatchKeys=8192 \
    -Dtd20.checkerStatsPeriodSeconds=10 \
    -Dtd20.checkerMemoryProgress=true \
    "$@"
