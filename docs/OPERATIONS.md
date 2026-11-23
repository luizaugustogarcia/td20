# Runtime operations

The independent checker owns its scratch storage implementation. Inverse
producers retain bounded per-thread key batches and distribute them across
deterministic persistence shards. Each shard deduplicates and sorts its own
window, checks exact persisted membership in batches of at most 4,096 keys,
builds an SST containing only absent keys, and ingests it without WAL. A key
always belongs to one serial writer, whose preceding ingest completes before
its next membership check. Existing values must be empty; lookup failures and
incomplete responses abort persistence. An entirely duplicate window creates
no SST. Scratch SSTs use whole-key Bloom filters with ten bits per key to
accelerate these exact lookups; filter positives never omit a key by themselves.
Their cached metadata uses the existing shared RocksDB memory budget.
All producer remainders and
writers drain before sealing. Parallel read batches decode and replay complete
lookup responses before processing misses; iterator errors, count mismatches,
malformed records and asynchronous failures abort verification. The checker
continues to regenerate its own obligations and never resumes emitter scratch
sets. Certificate databases remain read-only. Scratch sizing uses
`td20.reconstructionGeneratedBatchKeys`, `td20.reconstructionWriters`,
`td20.reconstructionQueueBatches`, `td20.reconstructionBufferKeys`, and
`td20.reconstructionScanBatchKeys`, `td20.reconstructionScanWorkers`, and
`td20.reconstructionScanRangesPerWorker`. Persistence retains the emitter's
bounded batching approach; the checker independently implements snapshot-range
scanning and validates every record. A changed checker must restart with fresh work.

The checker reclaims a completed scratch wave after its exhaustive source scan
and callbacks succeed and its successor has sealed successfully. Thus the
endpoint residuals, after-two states, second-level residuals and after-one
states retire in that order, rather than accumulating through the final wave.
Final roots retire after certificate replay and coverage-report publication.
Ordinary failure unwinding closes databases without deleting the remaining
source boundary. Cleanup applies only to directories created by this checker
run and never follows directory symlinks. Reclamation logs name each retired
wave and report the logical file bytes removed; persistence-drain progress
reports `duplicateKeysSkipped` separately from newly ingested keys.

Exact membership filtering and wave reclamation are checker storage safeguards
relative to the shared TD18 emission machinery. State-key version 3, generation,
sorting obligations, inventory checks and certificate formats are unchanged.
These safeguards prevent repeated occurrences from accumulating as physical
SST versions and limit retained data to the active source and successor waves.
Distinct classes, bounded queues and temporary SST/compaction files still need
space; there is no fixed full-run disk bound. Existing running JVMs keep their
loaded implementation; use the rebuilt checker with fresh work for these changes.

The checker packs canonical keys directly for certificate lookups and for
residual and inverse-state writes. This applies to roots, endpoints, all three
inverse waves and final-root lookups. Certificate hits retain full witness replay;
one replay-derived budget supplies both the project-maximum check and the
requested-budget comparison. Normalized inputs are reused for replay only after
an exact comparison with the computed canonical key. The version-2 key bytes remain compatible. The active target database requires
the new schema marker eriksson-three-v1; old databases may supply replayed
complete witnesses through the explicit source-database import. These changes reduce CPU work; counting,
generation coverage, disk I/O and compaction obligations remain as specified.

The default commands use the active route in
[the source paper](td20-reduction-count.tex).

## JShell, CPU and GPU configuration

`tools/td20 build` compiles both modules and prepares their dependency
classpaths. Every prepared Java entry point runs through JShell. CPU counts use
all JVM-visible processors, bounded generation queues and disjoint rank-placement
prefix tasks. `tools/td20 test` runs both required Maven CPU suites.
CUDA-dependent tests run only in a configured environment.

SLF4J logs in both modules default to `showDateTime=true`, with timestamps in
`yyyy-MM-dd'T'HH:mm:ss.SSSXXX` format (milliseconds and timezone offset).
The classpath `simplelogger.properties` files apply to JShell and packaged runs.

Pass execution options directly to `tools/td20`, including application
properties such as `-Dtd20.certificateReadWorkers=32` and VM options such as
`-R-Xmx24g` or `--jvm-option=-XX:ParallelGCThreads=16`. The same options
can be grouped in a quoted `-Dexec.args=...` or `-Dtd20.args=...` argument.
Application properties are applied before the execution JVM loads the entry
point. `TD20_JSHELL_OPTIONS` also accepts shell-style quoting, and command-line
JVM options take precedence. The launcher passes
`-R-Dtd20.countModule=emitter|checker` to keep report ownership separate.
Use `tools/run-local-emitter.sh --build` for the local SM 89 native library.
The generic `tools/td20 build-gpu` includes SM 89 and SM 120 native code, with
SM 120 forward PTX; this combined default requires CUDA Toolkit 12.8 or newer.
GPU sorting is entered only after applicable CPU prefixes and known-diameter
checks fail.

Instance 61821972 (host 51010, machine 153507) has two EPYC 9654 sockets,
384 allocated CPU threads and about 257 GB RAM. Its checker launcher uses
automatic allocation-aware CPU and reader counts, NUMA-aware G1 allocation,
a 32 GiB heap, a 128 GiB shared RocksDB budget and 4 GiB of input admission.
The 16 GiB memtable threshold is inside that shared budget. Native lookup
batches contain 4,096 keys, the cache uses 256 shards, and 16 writers persist
bounded reconstruction batches. These initial settings use the supplied
server specifications. Throughput on this instance
requires a measured run.

Run `tools/run-61821972-checker.sh --build`, inspect the execution arguments
with `--dry-run`, then launch `tools/run-61821972-checker.sh`. It accepts the
shared launcher options, including a quoted `-Dexec.args=...`. Caller arguments
override the instance defaults. The runtime records the effective settings.

Root emission skips generation when a complete published inventory row has
`U3 + O3 - C3 = 0`, including rows closed entirely by support clearing.
This is an emitter scheduling decision, not a checked count or proof closure.
Pending rows still fail. The independent checker regenerates every selected
root partition, compares all three counts and replays every direct obligation;
an incorrect published zero cannot omit proof-verification work.
Endpoint emission reads all 143 rows from the paper's versioned endpoint census
and skips zero-class types before any generation or counting. It also skips
elementary diameter closures. The reader rejects missing, duplicate, pending,
out-of-order or incompatible rows, annotations, domains, budgets and routes.
Nonempty types use their published class count for generation coverage checks.
The independent checker still computes its own endpoint census, generates
all types outside elementary closures and replays every resulting obligation.
The same scheduling applies when emission regenerates endpoint misses for
avoidance; missing obstruction markers never suppress an obligation.

Direct root and endpoint emission use `WitnessSearch.searchFresh`: their
generators already emit one representative per valid-pair class within each
type, so sorting begins with the CPU prefix without a preliminary certificate
database lookup. Source imports and avoidance miss regeneration retain
stored-certificate lookups. Reconstruction replays stored certificates with
one bounded `multiGet` per source batch before dispatch; its CPU and GPU miss
paths do not repeat individual `get` calls. Root prefetch also uses bulk
lookup, parallel CPU prefixes on its misses, and GPU fallback only for CPU
misses. Every new result still passes
certificate validation before persistence.

Concurrent checker reconstruction scans use one RocksDB snapshot and disjoint
half-open byte ranges covering its entire keyspace, including malformed keys.
Each worker owns an iterator, validates empty values without allocating value
arrays, decodes bounded batches, replays certificate lookups, then processes
misses directly. A shared bounded queue distributes ranges; there is no serial
iterator feeding all workers. `td20.reconstructionScanWorkers` defaults to
`td20.checkerWorkers` (all JVM-visible processors), capped by both the caller's
worker limit and the JVM's processor count.
`td20.reconstructionScanRangesPerWorker` defaults to four. More ranges can
reduce the idle tail when callback costs vary. Approximate byte sizes guide
range splitting only; equal estimates split breadth-first, including zero
estimates for unflushed data. Every inventory count comes from exact merged-key
iteration after successful callbacks. This replaces the serial iterator used
by the older TD18 checker without introducing an emitter dependency.

Inverse generation additionally caps callback batches at
`td20.reconstructionInverseScanBatchKeys` sources (16 by default). Set it to
one to publish progress after each completed source; certificate lookup scans
retain their configured bulk batch size. Generation progress also reports
queued key occurrences, ingested keys, skipped persisted duplicates and SST
ingestions. These persistence counters describe operational work; exact
inventories still require successful exhaustive scans and completed writer drains.

The batch size is
`min(td20.reconstructionScanBatchKeys, td20.certificateLookupBatchSize)`, with
defaults 4,096 and 32,768; known small inventories further reduce lookup batch
sizes. Each range drains its final partial batch. Callback order is globally
unspecified; serial `forEach` and `forEachBatch` retain key order. Counting and
checkpoint inventory validation use the same parallel decoding. Iterator
errors, malformed records, incomplete lookup responses, count mismatches and
worker failures abort the scan. The snapshot remains live until all workers
join, and an exact count is published only after every range and callback succeeds.
Set these properties directly through execution arguments, for example:

```bash
bash -ic './tools/td20 verify --phase=avoidance -Dtd20.reconstructionScanRangesPerWorker=16 -Dtd20.reconstructionScanBatchKeys=4096'
```

Resuming any sealed-wave checkpoint combines its validation with this mandatory
processing scan. The checkpoint count is an expectation for progress and final
coverage checks, never a completed inventory until the scan succeeds. A failed
scan cannot publish the next checkpoint.

GPU properties retain the shared machinery: `td20.gpuDevices`,
`td20.gpuSlots`, `td20.gpuBudgetMB`, `td20.gpuQueueCapacity`,
and `td20.gpuStatsPeriodSeconds`. CPU prefix properties are
`td20.cpuPrefixDepth`, `td20.cpuPrefixMaxTriples`, and
`td20.cpuPrefixMillis`, and `td20.reconstructionCpuPrefixMillis`. Limits bound a heuristic; they do not
remove the original sorting obligation.

The maintained local launchers detect allocated CPU cores and memory limits.
Their bounded defaults include a maximum 16 GiB heap, eight GPU slots and
768 MiB of queue memory per slot; these are configurable resource limits,
not TD20 measurements or hardcoded CPU allocations. Every run records its
own settings, revision, timestamps and status under the run root. Full-scope
independent verification of the completed TD20 certificate database succeeded
on 22 November 2026; see
[the execution record](REFERENCE_SIMULATION.md).

```bash
bash -ic './tools/run-local-emitter.sh --build'
bash -ic './tools/run-local-emitter.sh --dry-run'
bash -ic './tools/run-local-emitter.sh'
bash -ic './tools/run-local-checker.sh --build'
bash -ic './tools/run-local-checker.sh --dry-run'
bash -ic './tools/run-local-checker.sh'
```

Local data uses `certificates/` for the database, `emitter/target/native/`
for CUDA build output, and project-root `local-runtime/` for work, logs,
settings and temporary files. Computational runtime storage stays outside
Maven `target/` directories. The checker
always creates fresh work and reads certificates without writing them. Both
launchers share a runtime lock. The local emitter uses up to 8 persistence writers,
4,194,304 aggregate buffer keys, 512 queued batches, a 262,144-key certificate
queue and 32,768-key lookups. Its prefetch uses 4 preparation workers,
16 prepared batches, 32 input batches and a 262,144-key recent-key cache.
The local checker uses the same bounded state persistence and a separate
certificate read pool. Native lookup batches default to 32,768 keys, with
memory-based admission for asynchronous encoding, reading and witness replay.
Before a restart, the maintained launchers hold their runtime lock and remove
recorded abandoned interrupted work and temporary files. Cleanup preserves
the certificate database, the validated `--resume-work` source and every
completed-wave checkpoint; it does not follow directory symlinks. Logs and
settings retain execution history. No deletion occurs during `--dry-run`.

Override `TD20_RUN_ROOT`, `TD20_CERTIFICATE_DB`, `TD20_CPU_WORKERS`,
`TD20_HEAP_GIB`, `TD20_WRITERS`, `TD20_BUFFER_KEYS`, `TD20_QUEUE_BATCHES`,
`TD20_GENERATED_BATCH_KEYS`, `TD20_SCAN_BATCH_KEYS`, `TD20_LOOKUP_BATCH_KEYS`,
`TD20_READ_WORKERS`, `TD20_PRODUCER_BATCH_KEYS`, `TD20_CERTIFICATE_BUFFER_MB`,
`TD20_READ_CACHE_MB`, `TD20_READ_MAX_OPEN_FILES`, `TD20_GPU_SLOTS`, `TD20_GPU_BUDGET_MB`,
`TD20_GPU_QUEUE_CAPACITY`, `TD20_CERTIFICATE_WRITERS` or `TD20_MIN_FREE_GIB`
when required by the executing machine. Changing the local run root relocates
work, logs and settings; database and native paths keep their repository defaults.
The build accepts `TD20_CUDA_ARCHITECTURE` (89 by default),
`TD20_CUDA_HOST_COMPILER` and `TD20_BUILD_NATIVE_TESTS` (off by default).
It uses `g++-12` when available, for compatibility with CUDA 12.4 toolchains.
The installed toolkit must support the chosen architecture. For example,
this selects SM 120 native code and forward PTX on a compatible machine:

```bash
bash -ic 'TD20_CUDA_ARCHITECTURE=120 ./tools/run-local-emitter.sh --build'
```

Select GPU slot counts, queue sizes and JVM limits separately for the executing
machine; a compilation target does not determine those resource limits.

`--dry-run` reports settings without creating files or allocating GPU memory.
Before execution, the launcher checks available GPU memory for queues, caches
and a 1 GiB reserve; native initialization checks exact allocation sizes. The
32 GiB free-disk startup guard is not an estimate of full-run storage.
Ordinary GPU entry points default to 8 slots and 768 MiB per slot.
Other machines use explicit resource overrides with the maintained launchers.
All RocksDB writes disable WAL; completed phases explicitly flush certificates.
An interruption may lose unflushed results, which must be regenerated.

Reconstruction runs CPU searches, GPU dispatch and inverse generation in separate worker pools.
CPU workers scan the sealed source wave without waiting for GPU capacity;
only CPU misses enter an append-only disk queue. GPU workers drain that queue
and send every exhausted, depth-limited or timed-out class to a second
disk-backed queue. Dedicated CPU inverse workers reconstruct its parents.
GPU dispatch never waits for inverse generation or its output persistence. The CPU
prefix depth defaults to 5, with at most 8192 support checks and a 10 ms time
allowance per class. `td20.cpuPrefixDepth` overrides the depth; outside
reconstruction its default remains 3. The general CPU deadline
`td20.cpuPrefixMillis` defaults to 10 ms; the reconstruction-specific
property can override it. The support and time properties retain
their configured values. A CPU miss sends the original class and full budget
to CUDA. CUDA returns as soon as a generated prefix reaches identity or the
known diameter of its fixed-point-contracted remainder fits the remaining
budget, including during first-move seeding.

`td20.reconstructionGpuMillis` gives each intermediate reconstruction search
an optional device time allowance, defaulting to 0 (disabled). Intermediate
searches explore their full move budgets (9 or 10); the old depth-five cap and
`td20.reconstructionGpuDepth` override have been removed. Budget-11 reconstruction searches always
use full depth 11 without a time cutoff.
Ordinary root and endpoint emission searches retain their existing behavior.
The timer starts during first-move initialization on the device, includes device
scheduling from that point, and excludes waiting in the host dispatch queue.
CUDA checks it cooperatively between nodes and support batches, so the allowance
is not a hard deadline for the host call to return. A timeout produces no
certificate and leaves the class unresolved. All inverse parents remain
covered, either by the proved final-family elementary closure or explicit
inverse replay; an unresolved final residual root remains an error. The obligation budget
continues to govern admissible pruning and diameter closures. A depth-limited
search returns an explicit unresolved status without moves. GPU statistics
count depth-limited results among misses; timeouts remain a separate count.

CUDA explores a restricted sorting tree: supports avoid fixed symbols, the
number of odd-length cycles increases by zero or two, and supports in three
distinct algebraic cycles are excluded. The CPU prefix search does not apply
that last restriction. An exhausted CUDA search therefore supplies no distance
lower bound, even with the time allowance disabled and exploration cap removed.

`td20.reconstructionGpuWorkers` optionally controls the dispatch pool; its
default is twice the total GPU slots plus `td20.gpuQueueCapacity`, covering
active searches and the native dispatcher queue. These workers mostly wait
for GPU results and do not replace the CPU pool.

Log labels identify `Root emission partition`, `Endpoint emission type`,
`Certificate persistence`, `State inventory`, `State persistence`, or
`Inverse reconstruction` explicitly. Root emission reports completed sorting
obligations separately from `generationPrefixNodes` and
`generationStillRunning`; an empty sorting obligation count does not mean
generation has finished. Shared progress reports omit percentages for zero
totals instead of showing `0/0 (100%)` while generation is running.

Progress reports show processed/total, percentage, remaining source cases,
and separate cpuRemaining, gpuRemaining and inverseRemaining counts.
Processed is cached + cpuClosed + gpuClosed + inverseCompleted. A completed
inverse callback means its output has been queued; all writers still drain
before sealing. gpuSubmitted is cumulative and stays fixed after CPU
production ends. gpuMisses counts transfers to the inverse queue immediately.
td20.reconstructionInverseWorkers controls the inverse pool; its default is
the smaller of the CPU producer count and the JVM's available processors.
The native GPU statistics report timeouts separately from exhausted searches;
the reconstruction pipeline's unresolved GPU count includes both outcomes.
A wave completes only after CPU production, GPU dispatch and all inverse work
have joined. Coverage checks require every GPU input to be closed or transferred
and every transferred case to finish inverse processing.

For residual reconstruction, the completed budget-ten set is counted before
final inverse generation. After all writers drain, `countKeys()` scans
RocksDB's merged unique logical keys through parallel disjoint byte ranges on
one snapshot, without decoding or validating states,
running prefetch, or constructing a further deduplication set. The exact count
is saved in the `AFTER_ONE` checkpoint. Legacy version-three checkpoints with
`afterOne=-1` are counted and atomically upgraded before final generation.
Counting uses all available CPUs by default. The JVM properties
`td20.reconstructionCountWorkers` and `td20.reconstructionCountRangesPerWorker`
select the worker limit and task granularity (four ranges per worker by default).
Approximate range byte sizes guide scheduling; the inventory comes from exact
merged-key iteration. Logs show effective workers, completed ranges and keys.
The count can also be performed independently while reconstruction is stopped:

```bash
bash -ic './tools/td20 count-reconstruction --source-work=/path/to/completed-emitter-work'
```

The `Inverse expansion after-one family-filtered inverse states` line reports completed
expanded source classes as `processed=X/N (P%)`, with the exact source total
`N`, percentage `P=100 X/N` for a nonempty source, remaining classes, throughput
and `etaAtAverageRate`. A completed source callback has covered both inverse
endpoints through analytic family closure or explicit replay and queued its
non-elementary final-root outputs; asynchronous writers may still need to drain.
`rootInventory=ELEMENTARY_RESIDUALS` identifies the output meaning.
`candidateSlots`, `familyClosedSlots`, `closedEndpointFamilies`, `replayedParents`
and `immediateClosedParents` separate coverage categories; slots may include
invalid parents, and none counts distinct closed root classes. That same line reports
`generatedKeyOccurrences`, `submittedKeyOccurrences`,
`consumedKeyOccurrences`, `ingestedKeyOccurrences`, `queuedKeyOccurrences` and
`ingestedTables` separately. These accumulated output and write counters can
include duplicate canonical keys. `outputUniqueClasses` stays unknown until
the mandatory final-root database scan establishes the distinct total.
Advisory prefetch reports handled, submitted-so-far and pending occurrences
without an expansion completion percentage.

The final residual-root inventory is counted during the mandatory root-processing scan.
Persistence writers drain before that scan starts; there is no preliminary
count-only traversal. Until the iterator completes, progress reports an unknown
total. The iterator validates every record and its status, and processing still
checks all CPU/GPU/inverse coverage equalities. The ROOTS checkpoint is written
with the exact count only after the processing workers drain. An interruption
before that boundary resumes the preceding sealed wave. The independent checker
continues to regenerate and verify its own inventories.

The capped CUDA search combines the zero-move allowance with the invariant
that rank-decreasing moves preserve all-odd cycle structure. Its table-driven
subtree rejection leaves the class unresolved and preserves every inverse
obligation; the source paper proves the rule. A zero move here preserves the
odd-length-cycle count, independently of its fixed-symbol gain.
GPU consumers only dispatch searches and transfer misses. The separate inverse
pool can wait for persistence without preventing further GPU submissions.
td20.reconstructionGeneratedBatchKeys defaults to 65,536 keys.
Inverse generation batches these canonicalized keys into independent writer
queues; deduplication and disk writes have no global producer lock. Every
producer buffer is retained across callbacks and its final partial batch is
queued after all generators join. Every writer drains and joins before sealing.
td20.reconstructionWriters defaults to at most eight writers.
td20.reconstructionBufferKeys defaults to 16,777,216 aggregate keys;
td20.reconstructionQueueBatches defaults to 1,024 aggregate batches.
Both capacities are divided across shards with rounding up. The local
launcher selects up to 8 writers, 4,194,304 buffer keys and 512 queue batches.
With 8 writers, each has a 524,288-key buffer budget, equal to the database
batch limit. The effective window is the smaller of that batch limit and the
aggregate buffer divided across writers with rounding up. Queues remain
bounded, and full queues apply backpressure when storage cannot keep up.
Writers sort their deduplication windows and build SST files independently.
Ingestion bypasses the per-key WAL/memtable write path; overlapping keys
retain the same empty value under global sequence numbers. Up to sixteen
background jobs and eight subcompactions handle subsequent compaction.
DEBUG persistence logs report table construction and completed ingestion with
key counts, bytes and elapsed time. Failed construction or ingestion makes
the wave unsealable; interrupted waves are regenerated from the sealed source.
The inverse completion counter records finished generation and encoding.
Producer remainders and queued tables must still drain before the persisted
inventory is validated and the wave checkpoint is written.
Root GPU prefetch consumes encoded output while reconstruction continues,
starting immediately with one worker per GPU slot. Its bounded RAM queue holds up to
16,777,216 keys in 256 full producer batches; a bounded recent-key cache
suppresses repeated early checks. Queued advisory work can be discarded
when switching to the complete sealed-root scan. Advisory searches use
td20.rootPrefetchGpuMillis (100 ms by default, required positive), at full
depth. Incomplete early searches write no obstruction markers. Ordinary
final-root searches remain full depth with no timeout. All certificate
validation stays in the normal search
path. Early-search counts are scheduling statistics, not final inventory
or proof coverage counts.

## Budgets and persistence

The maintained local emitter defaults to `td20.gpuSlots=8` and `td20.gpuBudgetMB=768`,
for 6 GiB of GPU queues plus state caches and metadata.
Per-run settings under `local-runtime/logs/` record the actual JVM options,
source revision, work path, start/finish times and completion status.

| State | Symbols | Budget |
| --- | ---: | ---: |
| Direct/final root | 21 | 11 |
| Endpoint | 16 | 8 |
| After two moves | 18 | 9 |
| After one move, gain-one branch | 20 | 10 |
| After one move, gain-zero branch | 21 | 10 |

A domain alone no longer determines the obligation budget: 21-symbol states
can occur with budgets 10 and 11. Certificate persistence therefore
validates intrinsic results against the project maximum, and lookups replay them
against the explicit requested budget. A valid longer result is a miss for a
smaller budget. Malformed results are errors. Intrinsic witnesses are independent of the route and reusable after replay;
checkpoints must match the exact active route and format.

All database writers use `td20.dbWriteBatchEntries`, defaulting to 524,288
entries (512 Ki entries). This limits certificate `WriteBatch` entries,
inverse-state external SST keys per writer, and independent checker-state
`WriteBatch` keys. Smaller certificate queues or per-writer reconstruction
buffers further limit batch sizes. Final partial batches still drain at
completed persistence boundaries. A certificate entry can produce both a
witness put and an obstruction delete, so entries do not count individual
RocksDB operations.

Certificate persistence also uses `td20.certificateQueueCapacity` and
`td20.certificateFlushSeconds`. Inverse sets use
`td20.reconstructionBufferKeys` and batched reads controlled by
`td20.certificateLookupBatchSize`. Queues and in-memory key buffers are
bounded. Class keys and disk iteration are deterministic at persistence
boundaries. `certificates/`, `data/`, work databases and ordinary logs are untracked.
Completed execution logs and settings also remain untracked.

The checker defaults to 32,768 keys per native `multiGet`.
`td20.certificateLookupBatchSize` or `TD20_LOOKUP_BATCH_KEYS` changes that
actual call size. CPU encoding and witness replay use `td20.checkerWorkers`;
native reads have a separate bounded pool sized by `td20.certificateReadWorkers`,
which defaults to the detected CPU worker count. Producer workers own private
buffers of `td20.certificateProducerBatchKeys` keys (default 4,096), and publish
them to a bounded dispatcher queue without waiting inside a shared buffer lock.
The dispatcher assembles native batches and overlaps encoding, reading and replay.
Partial batches and reconstruction scan limits can produce smaller calls.

Admission uses `td20.certificateBufferedMB`, defaulting to one third of maximum
Java heap, bounded between 128 and 4,096 MiB. Each admitted pair is conservatively
accounted as 512 bytes until its witness replay and missing-result callback finish.
This is an estimate of Java work retained by the pipeline, not an exact heap limit;
producer-private tails and generation/storage allocations are additional.
The budget must hold at least one native batch. All private tails and asynchronous
results drain before a phase completes, and count mismatches or failures reject
verification. Regular ten-second progress lines show the type, progress
and interval throughput. Root generation reports completed/discovered
prefix tasks and visited nodes per second; the discovered count grows during
scheduling. Endpoints report checked/total classes and classes per second. Each type emits
a completion line. `td20.checkerVerboseProgress=true` additionally reports
generation, buffering, witness hits, native reads, cache and heap diagnostics.

Witnesses, obstruction metadata and checker scratch databases share one execution-wide
RocksDB cache. Its strict capacity includes index/filter blocks by default. A shared
WriteBufferManager charges memtables to the same cache budget and stalls writes at
its configured threshold. Native resources stay alive until all database owners
release them. The source certificate database remains read-only. All witness replay,
generation, domain, budget, inventory and interruption checks remain unchanged.

Every setting can be overridden through execution arguments. The local
launchers accept `--cpu-workers=N`, `--heap-gib=N`, `--run-root=PATH`, and
`--database=PATH`. Use `-Dtd20.NAME=value` for any application property below
and `--setting=TD20_NAME=value` for a launcher setting. JVM options use
`-R...`, `-J...`, or `--jvm-option=...`. Quoted `-Dexec.args=...` and
`-Dtd20.args=...` accept the same arguments. Run settings record the
execution arguments, JVM options and effective application property values.

| Environment override | JVM property / purpose |
| --- | --- |
| `TD20_HEAP_GIB` | Java heap limit |
| `TD20_ROCKSDB_MEMORY_MB` | `td20.rocksDbMemoryMB`, aggregate managed cache budget |
| `TD20_ROCKSDB_WRITE_BUFFER_MB` | `td20.rocksDbWriteBufferMB`, memtable threshold inside that budget |
| `TD20_ROCKSDB_STRICT_CAPACITY` | `td20.rocksDbStrictCapacity`, strict cache capacity |
| `TD20_ROCKSDB_CACHE_METADATA` | `td20.certificateCacheIndexAndFilterBlocks`, charge indexes/filters |
| `TD20_ROCKSDB_FILL_CACHE` | `td20.rocksDbFillCache`, populate cache during reads |
| `TD20_ROOT_FILL_CACHE` | `td20.certificateRootFillCache`, root data-cache policy (default true) |
| `TD20_ENDPOINT_FILL_CACHE` | `td20.certificateEndpointFillCache`, endpoint data-cache policy (default true) |
| `TD20_RECONSTRUCTION_FILL_CACHE` | `td20.certificateReconstructionFillCache`, reconstruction data-cache policy (default true) |
| `TD20_ROCKSDB_CACHE_SHARD_BITS` | `td20.rocksDbCacheShardBits`, cache shard configuration |
| `TD20_ROCKSDB_CACHE_HIGH_PRIORITY_RATIO` | `td20.rocksDbCacheHighPriorityRatio`, priority pool fraction |
| `TD20_ROCKSDB_WRITE_STALL` | `td20.rocksDbWriteStall`, memtable backpressure |
| `TD20_ROCKSDB_MEMTABLE_MB` | `td20.rocksDbMemtableMB`, individual scratch memtable size |
| `TD20_ROCKSDB_MAX_WRITE_BUFFERS` | `td20.rocksDbMaxWriteBuffers`, memtables per scratch database |
| `TD20_ROCKSDB_SCRATCH_MAX_OPEN_FILES` | `td20.rocksDbScratchMaxOpenFiles`, scratch table-reader limit |
| `TD20_READ_WORKERS` | `td20.certificateReadWorkers`, simultaneous native read limit |
| `TD20_LOOKUP_BATCH_KEYS` | `td20.certificateLookupBatchSize`, keys per native read |
| `TD20_READ_MAX_OPEN_FILES` | `td20.certificateMaxOpenFiles`, source table-reader limit |
| `TD20_CERTIFICATE_BUFFER_MB` | `td20.certificateBufferedMB`, Java input admission |
| `TD20_MEMORY_PROGRESS` | `td20.checkerMemoryProgress`, memory diagnostics |

The managed cache defaults to the existing `td20.certificateReadCacheMB` budget
(one quarter of Java heap, between 64 and 4,096 MiB), and its memtable threshold
defaults to one eighth of that managed budget. The maintained checker launcher
defaults to 32,768-key lookups and the detected CPU worker count for native
readers, generation and replay. Tune native readers and batch size together
using sustained generation-and-replay measurements on the allocated server.
Source/scratch table-reader limits default to 512/128, individual memtables to
64 MiB with four buffers, and all limits have the overrides listed above.

The cache limit is not a hard cap on total RocksDB RSS: native read temporaries,
table-reader objects, compression buffers and other allocations need headroom.
Concurrent reads, lookup sizes and cached table counts therefore remain bounded
separately. Strict-cache exhaustion fails verification instead of accepting missing
data; write-buffer backpressure flushes existing memtables. Ten-second progress
includes Java heap, managed cache usage, pinned cache usage, the memtable threshold
and process RSS. Launch settings record all effective JVM options.
All three phases populate the bounded data-block cache by default. Distinct
witness keys can share compressed SST blocks, so checking each key once still
benefits from retaining decompressed blocks. Root, endpoint and reconstruction
cache policies have independent execution overrides. Index/filter metadata
also uses the bounded shared cache.
Phase transitions occur after preceding reads and replays drain. Read options
are immutable while in use, and both single and bulk lookups use the selected policy.

Each handoff file has format `td-reconstruction-queue-v1`, a route/domain/budget
header, fixed-size records containing versioned canonical class keys and their
explicit budgets, and a final record count. An unfinished file keeps count -1.
Queue memory is bounded independently of its disk backlog; truncated records
and incomplete coverage are errors. Handoff files are temporary execution state,
never proof inputs or resume sources. After interruption, resume regenerates
the handoff from the latest complete sealed wave and replays stored certificates.

Each sealed-wave checkpoint has route ID `td20-eriksson-three-v1`, explicit
budgets and counts, and its own versioned file `eriksson-reconstruction-v1.txt`.
Filtered residual-mode `ROOTS` checkpoints use header
`td-eriksson-reconstruction-v4` and `rootInventory=ELEMENTARY_RESIDUALS`;
their root count excludes the independently checked elementary closures.
Earlier completed after-one inputs remain compatible, while old all-parent
root checkpoints retain their different explicit inventory meaning.
Resuming validates every record of the latest sealed set and its exact count.
Changing the GPU time allowance does not change the route or state format.
The checker requires fresh work outside the certificate database and never trusts
or resumes emitter sets. Checksums, obstruction markers and sidecar manifests
cannot replace regenerated proof obligations.

## Entry points and options

`emit` accepts `--phase=roots|endpoints|avoidance|all`,
`--partitions=FIRST-LAST`, `--endpoints=FIRST-LAST`, `--paper=PATH`,
`--database=PATH`, `--work=PATH`, and `--source-db=PATH`.
`--resume-work=PATH` requires the complete avoidance scope without ranges or a
witness-source import. `verify` supports the same phase/range selection and
fresh `--work`, but never emitter checkpoint inputs. `inventory` performs only
structural checks. Source counts and full sorting verification remain separate.

Certificate persistence assigns each canonical key to one writer so updates remain
ordered across batches. Before replacing a stored certificate, its replay-derived
budget is compared with the new result; a weaker result cannot erase the strongest
known bound. Certificate-only batches build ordered SSTs without WAL. In mixed
batches, unmarked certificate entries remain on that path while obstruction
updates use their ordered write batch. Failed writes propagate to producers, and
explicit flushes establish completed persistence boundaries. A stored obstruction
never substitutes for independently regenerated work.
