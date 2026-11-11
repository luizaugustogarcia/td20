# Computational operations

Run commands from the repository root. JDK 21, Maven, Bash, and Python 3 are
required for CPU work. The project implementation is Java with native CUDA
sorting sources. Standalone C++ programs under tools/audit are independent
historical audits and are not required by the Java counting workflow.
See [the workflow](WORKFLOW.md) and [the research plan](RESEARCH_PLAN.md).

## Inventory and CPU validation

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
python3 -B -m unittest discover -s tools/audit -p 'test_*.py'
tools/td20 inventory
tools/td20 structure
~~~

When the TD(18) checkout is available, check shared machinery before changing
generation, counting, scheduling, search, or persistence:

~~~bash
python3 -B tools/audit/check_td18_machinery.py --reference ../td18
~~~

This compares shared Java, CUDA, and build sources with explicit domain and
budget adaptations. The joint-3 adapters remain covered by the independent
small-domain Java checks and the source specification.

The required inventory is 83 root partitions and 104 endpoint annotations,
including 52 annotations with the elementary oriented-3-cycle closure. Tests
must include supports inside even-length cycles and across cycles. A structural
check is not a sorting certificate.

## Exact counting on all CPU cores

The Java machinery matches TD(18). Bracelet generation and Burnside counting
use the same worker detection and disjoint prefix tasks. Joint emission and
independent regeneration use the TD(18) bounded pools and prefix depths.
Counting coordinates partitions on a pool sized from the CPUs visible to the
JVM; inverse reconstruction submits class work through bounded queues.

~~~bash
python3 -B tools/audit/continue_joint3_counts.py --resume
~~~

This command detects concurrency itself. Do not add machine-specific worker or
shard arguments. Complete partitions are atomically published. An output-directory
lock rejects concurrent runners. Resumption validates deterministic keys, class
splits, support-clearing buckets, formats, and complete legacy shard groups.
Unfinished partitions restart; completed native groups remain reusable.
Incomplete legacy shards must be archived before switching to Java resumption.
Generated artifacts, archives, and logs remain untracked.

The continuation uses Java for unfinished counting stages, publishes complete
paper rows as they arrive, counts endpoints, reconciles the summary, runs both
CPU suites, and builds TeX. It invokes no sorting or certificate emission.
The Java run logs to logs/joint3-count-pipeline-java-v1.log.

~~~bash
tools/td20 count-u '-Dexec.args=--output=data/joint3-reduction-audit/unoriented-v1.json'
python3 -B tools/audit/run_java_joint_counts.py --resume
tools/td20 count-e '-Dexec.args=--output=data/joint3-reduction-audit/endpoints-v1.json'
~~~

The combined summary records U, J, C, O, U+O, and endpoint totals. Every source
retains implementation provenance. Native Burnside term v2 is a valid independent
audit only after shard numerators are summed before repeated-cycle multiplicity
division; older term v1 is rejected. Java Burnside mathematics is unchanged by
the scheduling update. The standalone joint runner accepts --paper and
--unoriented together when automatic table publication is wanted; the continuation
supplies these automatically.

## Emission and inverse coverage

`WitnessEmitter` accepts phase, partition range, endpoint range, paper,
database, and work-directory options. The exact supported arguments are shown
by `tools/td20 help` and the command's `--help` output. Typical invocations on
a configured CUDA machine are:

~~~bash
tools/td20 emit -Dtd20.certificateDb="$PWD/certificates" \
  -Dtd20.args=--phase=roots
tools/td20 emit -Dtd20.certificateDb="$PWD/certificates" \
  -Dtd20.args=--phase=endpoints
tools/td20 avoidance -Dtd20.certificateDb="$PWD/certificates"
~~~

Direct roots use budget 11 and endpoints use budget 8. Avoidance has three
distinct budgets and domains:

| Wave | Reversed gain | Domain | Budget |
| --- | --- | ---: | ---: |
| Third move, tag `(1,1,3)` | 3 | 19 | 9 |
| Third move, tag `(1,2,2)` | 2 | 18 | 9 |
| Tagged second move | 1 or 2 | 20 | 10 |
| First move | 1 | 21 | 11 |

The endpoint tag is first-class data. If canonical deduplication merges paths,
the stored work item must retain every applicable tag. Every wave verifies the
forward move before accepting a predecessor. A cache hit is replayed under that
wave's budget; a miss or CPU-prefix timeout sends the unchanged obligation and
full wave budget to GPU search.

Work directories must be outside the certificate database and fresh unless a
documented sealed checkpoint is being resumed. Checkpoint counts are operational
integrity data, not proof inputs. Preserve the source checkpoint directory when
resuming into new work.

## Parallelism and bounded memory

The local TD(18) certificate snapshot measured on 2026-11-11 occupies 49.4 GB.
Its Snappy-compressed witness tables occupy 49.25 GB for 4.967 billion table
entries, approximately 9.92 bytes per entry. Table entries can include multiple
versions; this measurement is a storage sample, not a verified class count.

Applying that density to the 15,008,059,966 direct roots and 4,511,223,147
endpoints requiring individual discharge gives an initial 193.5 GB projection.
Plan for approximately 200–300 GB for those certificate tables: TD(20) root
keys occupy fourteen bytes rather than TD(18)'s twelve, and actual witness
lengths and compression can change the average. The 2,574,414,059 endpoints
with the elementary closure are handled structurally by the emitter.

Provision approximately 1 TB of working space as an initial planning budget
for certificates, compaction, and reconstruction/checker work databases. This
is not an upper bound: the three inverse-wave inventories are unknown until
unresolved endpoints and their deduplicated predecessors are generated.

Generation, canonicalization, disk deduplication, lookup, and replay use the
same worker pools and queues as TD(18). Relevant properties include:

| Property | Purpose |
| --- | --- |
| `td20.reconstructionBufferKeys` | Unique keys buffered before disk flush. |
| `td20.certificateLookupBatchSize` | Keys per batched certificate lookup. |
| `td20.certificateQueueCapacity` | Pending certificate writes. |
| `td20.gpuDevices`, `td20.gpuSlots` | CUDA device and slot selection. |

CPU concurrency is detected automatically. Defaults keep snapshots and persistence
buffers bounded. Compare identical class scopes and budgets when measuring performance.

The CPU prefix and CUDA move ordering are search heuristics. They may prioritize
positive fixed-point gains and known-diameter exits, including at totally
unoriented states, but they do not remove the original full-budget obligation.
The structural joint-3 reduction is used only for root coverage.

## Certificate checking

~~~bash
tools/td20 verify -Dtd20.certificateDb="$PWD/certificates"
~~~

The checker uses all available processors by default. It independently
regenerates root and endpoint inventories and all three inverse waves, then
replays every result. Complete records must reach identity. Known-diameter
closures must satisfy `used moves + diameter(contracted remainder) <= budget`.
The accepted maximum budget is 11.

Obstruction markers are work records, not lower-bound proofs. Missing markers,
sidecar manifests, and checksums cannot remove regenerated obligations. A phase
or range selection verifies only that scope. Full success requires no unresolved
direct root, endpoint path, intermediate, or reconstructed root.

## Build the source specification

~~~bash
mkdir -p /tmp/td20-paper-build
pdflatex -interaction=nonstopmode -halt-on-error \
  -output-directory=/tmp/td20-paper-build docs/td20-reduction-count.tex
pdflatex -interaction=nonstopmode -halt-on-error \
  -output-directory=/tmp/td20-paper-build docs/td20-reduction-count.tex
~~~

The final source table must contain exactly 83 machine-readable `jointrow`
commands in independently regenerated order. Until the complete count artifacts
exist, the source publishes completed components and marks the rest pending. Generated PDFs, count
artifacts, databases, checkpoints, work sets, logs, and certificates remain
untracked.
