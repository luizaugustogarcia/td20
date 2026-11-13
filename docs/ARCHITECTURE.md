# Architecture

Joint-2 includes totally unoriented roots with even-length cycles. All-odd
totally unoriented roots and all totally unoriented successors stop for the
reasons proved in the source paper. The active direct count is R + O; R counts
unoriented residuals. All 83 rows are checked, with R = 124,395,153. See
[WORKFLOW.md](WORKFLOW.md) for Java counting and the versioned artifact formats.

The mathematical contract is [the source paper](td20-reduction-count.tex).
The workflow has three boundaries: exhaustive class generation, sorting
search, and independent certificate verification.

## Mathematical core

A cyclic-target candidate is unchecked until its current cycle, even
algebraic permutation, and full target have been validated. The general
model preserves fixed points. Fixed-content generators require a contracted
fixed-point-free pair. Candidate-orbit keys and valid-pair class keys are
distinct immutable values.

The joint package contains the diameter table, paper-backed root inventory,
independently enumerable endpoint inventory, joint transposition analysis,
and explicit support-clearing patterns. The analysis tests every qualifying
first move and all second moves creating at least two fixed points. It does
not select a cycle type before testing move availability.

The fixed-content bracelet generator handles annotated endpoints. Totally
unoriented residual roots use the constrained generator with reverse
current-cycle support order enforced at every completed cycle. The constrained root generator fixes algebraic cycle
labels, assigns current-cycle positions, and prunes favorable-prefix
constraints during recursion. Both enforce twisted symmetry and full-target
realizability before emitting a class.

## Emitter

The emitter directly generates totally unoriented residual roots and oriented joint
residuals, validates the paper counts, and applies the cited support-clearing
closures. Every other direct root is searched with budget eleven. Endpoints use
nine and reconstructed intermediates use ten.

The CUDA sorter allows transpositions with odd-length-cycle gains zero or
two. It searches up to the complete budget and stops at identity or when
contraction leaves a domain whose established diameter fits the remaining
budget. Fixed points are counted as data at the root and updated after each
transposition. For direct roots and endpoints, a bounded CPU prefix tries actual transpositions
and the same identity or diameter exits. It prioritizes immediate exits on
consecutive algebraic supports and then larger fixed-point gains, including
at totally unoriented states. These are witness-search orderings, not structural
reductions. A prefix miss retains the original pair and full GPU budget.

CUDA contracts fixed symbols for general support enumeration and records
moves in the original current-cycle coordinates. Shared cycle metadata
replaces per-support cycle walks. The consecutive-triple exit test suffices
at the last depth, where no general child can be queued; see OPERATIONS.md
for the diameter-zero argument and the independent cycle-arithmetic audit.

Complete witnesses and diameter closures are validated and persisted with
separate kind tags. CUDA initialization is lazy. The certificate writer uses
a bounded queue of 16,384 entries by default, configurable through
td20.certificateQueueCapacity. The joint-generation and root-witness task
queues each permit 256 pending tasks. CPU worker counts are automatic.
Each disk set buffers at most 1,048,576 unique keys before flushing to RocksDB,
configurable through td20.reconstructionBufferKeys.
Emitter orbit minimization compares spatial images in the fixed alphabet
using primitive buffers and constructs only the winning auxiliary-standardized
word. Twisted reflection still reverses positions and inverts oriented ranks.
Emitter certificate replay uses primitive permutation images; the independent
checker retains its separate object-based orbit and replay implementations.

Avoidance restores two fixed points to each unresolved endpoint and reverses
every compatible second move. Disk-backed class deduplication precedes GPU
search with budget ten. Batched multiGet replays stored witnesses before
trying each missing class in the bounded CPU prefix for at most the configured
reconstruction deadline. A timeout sends the untouched obligation directly to
GPU search. Successful intermediate discharges are persisted.
The second inverse wave restores one fixed point and reconstructs roots;
remaining roots use the same timed CPU prefix followed by budget-eleven GPU
search on every prefix miss or timeout. The root result starts at the root and
is independent of the selected reduction prefix.

## Checker

The checker has no dependency on the emitter artifact. It owns independent
inventory generation, root generation, inverse construction, and replay.
Its interface to the emitter is the mathematical certificate encoding.
The source inventory is checked against independently regenerated partitions
and endpoint annotations. Its structural mode validates the finite route
and tests without pretending to check a certificate database.

Proof verification regenerates every selected root and endpoint. Each endpoint
without a nine-move discharge triggers inverse coverage even when no
obstruction marker is present. Regeneration also makes sidecar manifests
unnecessary as proof inputs. A longer valid witness does not close a smaller
budget; an incorrect witness is rejected.

Independent rank-placement regeneration is divided into disjoint prefix
subtrees across all checker CPUs. Direct twisted-bracelet generation and both
inverse waves are likewise parallel. Certificate keys are read with large,
position-preserving RocksDB multiGet batches; every returned record is still
decoded and replayed independently in parallel before it can discharge an
obligation. Checker reconstruction sets use large bounded in-memory key
buffers followed by disk-backed global deduplication. Parallel scheduling and
batching change only throughput, not the generated inventories or acceptance
conditions.

## Persistence

The certificate database has two RocksDB column families. The default family
contains the UTF-8 schema entry td20-schema = joint2-selection-v2 and raw
canonical instance keys mapped to complete witnesses or diameter closures. The obstructions family
maps raw keys to empty values. The standard current cycle is implicit in the
five-bit key, so pairs are canonicalized before writing. Each transposition
uses three five-bit symbols. Each value starts with a kind byte and an exact
move-count byte. Zero-move closures have a two-byte record, so empty values
are never treated as sorting proofs. The checker replays every stored move,
requires identity for complete records, and derives the remaining diameter
from the actual nonfixed symbols for closure records.

A separate optional TD(18) source is opened read-only. Only replayed witnesses
within the requested budget are reused. Operational reconstruction work sets hold deterministic extended-toric
class keys on disk and require fresh directories for each invocation. Reruns
regenerate work and reuse validated certificates. Scratch data does not
replace regeneration in the proof checker. Generated databases, work sets, checkpoints,
logs, and certificates remain untracked.

The documented scheduling exception in TD(20) applies the same ten-millisecond
CPU prefix deadline to roots, endpoints, and reconstructed classes through
td20.cpuPrefixMillis. The source TD(18) applies a deadline to reconstruction.
Misses and timeouts preserve the unchanged class and full GPU budget.

The form comparison in target/form-audit.md covers all 177 corresponding
TD(18) source paths, with no missing files after project naming. It records
the additional counters and audit tools individually. Guide and paper section
outlines, table columns, root continuation headers/footers, totals, and the
PDF location follow TD(18). The report is an implementation comparison, not
a proof certificate. Mathematical parameters and checked scopes differ.
