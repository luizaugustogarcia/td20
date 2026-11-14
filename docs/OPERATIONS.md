# Runtime operations

The default commands use the active route in
[the source paper](td20-reduction-count.tex).

## JShell, CPU and GPU configuration

`tools/td20 build` compiles both modules and prepares their dependency
classpaths. Every prepared Java entry point runs through JShell. CPU counts use
all JVM-visible processors, bounded generation queues and disjoint rank-placement
prefix tasks. `tools/td20 test` runs both required Maven CPU suites.
CUDA-dependent tests run only in a configured environment.

Set `TD20_JSHELL_OPTIONS` for execution-VM options, for example
`-R-Xmx3g` or `-R-Dtd20.twistedBraceletWorkers=auto`. The launcher passes
`-R-Dtd20.countModule=emitter|checker` to keep report ownership separate.
Use `tools/td20 build-gpu` for native CUDA libraries. GPU sorting is entered
only after applicable CPU prefixes and known-diameter checks fail.

GPU properties retain the shared machinery: `td20.gpuDevices`,
`td20.gpuSlots`, `td20.gpuBudgetMB`, `td20.gpuQueueCapacity`,
and `td20.gpuStatsPeriodSeconds`. CPU prefix properties are
`td20.cpuPrefixDepth`, `td20.cpuPrefixMaxTriples`, and
`td20.cpuPrefixMillis` (one common deadline for roots, endpoints, and inverse states). Limits bound a heuristic; they do not
remove the original sorting obligation.

Reconstruction runs CPU searches and GPU dispatch in separate worker pools.
CPU workers scan the sealed source wave without waiting for GPU capacity;
only CPU misses enter an append-only disk queue. GPU workers drain that queue
concurrently and reconstruct parents only after an actual GPU miss. The CPU
depth, support limit and time allowance retain their configured values.
`td20.reconstructionGpuWorkers` optionally controls the dispatch pool; its
default is twice the total GPU slots plus `td20.gpuQueueCapacity`, covering
active searches and the native dispatcher queue. These workers mostly wait
for GPU results and do not replace the CPU pool.

Progress reports identify each wave and show its total, cached results, CPU
closures, queued cases, GPU closures, GPU misses and remaining backlog.
A wave completes only after all CPU work and GPU results have joined and its
coverage counts agree. Increasing queue capacity does not increase GPU capacity.

## Budgets and persistence

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

Certificate persistence uses `td20.certificateQueueCapacity` and
`td20.certificateFlushSeconds`. Inverse sets use
`td20.reconstructionBufferKeys` and batched reads controlled by
`td20.certificateLookupBatchSize`. Queues and in-memory key buffers are
bounded. Class keys and disk iteration are deterministic at persistence
boundaries. `certificates/`, `data/`, `logs/` and work databases are untracked.

Each handoff file has format `td-reconstruction-queue-v1`, a route/domain/budget
header, fixed-size records containing versioned canonical class keys and their
explicit budgets, and a final record count. An unfinished file keeps count -1.
Queue memory is bounded independently of its disk backlog; truncated records
and incomplete coverage are errors. Handoff files are temporary execution state,
never proof inputs or resume sources. After interruption, resume regenerates
the handoff from the latest complete sealed wave and replays stored certificates.

Each sealed-wave checkpoint has route ID `td20-eriksson-three-v1`, explicit
budgets and counts, and its own versioned file `eriksson-reconstruction-v1.txt`.
Resuming validates every record of the latest sealed set and its exact count.
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
