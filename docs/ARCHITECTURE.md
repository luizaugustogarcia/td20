# Computer-assisted proof architecture

The [paper](td20-reduction-count.tex) specifies the mathematics and
[research plan](RESEARCH_PLAN.md) states proof completion criteria. The public
`WitnessEmitter` and `CertificateChecker` entries dispatch to their active
`ErikssonWitnessEmitter` and `ErikssonCertificateChecker` implementations.
The Maven modules remain independent: the checker has no emitter dependency.

Production emission, independent checker generation, reconstruction, import,
merging, and certificate replay use immutable `PrimitivePair` byte vectors and packed `PrimitiveCertificate`
transpositions. Worker-owned arrays perform inverse enumeration, canonicalization,
CPU prefixes, and replay. Inverse callbacks pack canonical state keys directly
into bounded persistence batches, without disjoint-cycle or encoding objects.
The general mathematical APIs and comparison harness retain legacy adapters.
Both modules now use the same primitive representation, each compiled into its
own artifact. The mathematical route, counter, scheduling, and persisted formats
are preserved. The checker retains its legacy mathematical APIs as comparison
oracles; production generation, inverse persistence and batched witness replay
do not construct disjoint-cycle objects.
The bounded comparison and correctness scope are documented in
[PERFORMANCE_EXPERIMENTS.md](PERFORMANCE_EXPERIMENTS.md).

`ErikssonRoute` defines the root domain 21, budget 11, endpoint domain
16, budget 8, and 143 endpoint annotations. `ErikssonInventory`
reads every active paper row in deterministic partition order. Explicit pending
rows support structure checks but cannot supply proof counts.

The emitter root stream uses `ErikssonRootGenerator` with normalized algebraic
cycles and prefix placement. `CyclicTargetMoves` contains mathematical move and
contraction operations. The checker has its own independent move implementation
and a budgeted `SearchState`; neither requires a superseded route container.
Source-side immediate and three-move constraints, forced target-cycle checks,
and twisted canonical comparisons prune prefixes. Known directed target paths
also certify immediate target-side closures. Full remaining leaves pass the
complete two-sided reduction test. Count mode retains no representative set.

The checker derives its own source predicates by arbitrary supports, uses
independent rank-placement state, and tests both full cycles directly. Its
selection does not call the emitter's endpoint-swapping or consecutive-triple
helpers. Both implementations are compared with independent small-domain
permutation oracles before large runs.

The checker's orbit minimum compares fixed-alphabet images in primitive arrays.
Cycle size and orientation determine each alphabet block; colors within an
equal annotated type are numbered by their first occurrence in the image.
Oriented ranks are measured from that first occurrence, with both positions
and ranks inverted for twisted reflection. These are the same auxiliary
standardization and spatial action described in [the conventions](KNOWLEDGE.md).
A first larger differing symbol rejects an image immediately. Strict replacement
preserves the previous rotation/reflection order on ties. Only the winning
image becomes a constructed, fully validated encoding word. This preserves
the canonical representative and persisted keys; it changes no obligation,
inverse support, forward replay or certificate check. An independent full-word
orbit reference checks the primitive comparison in the checker test suite.

Checker auxiliary standardization uses primitive cycle identifiers, labels and
rank arrays, including singleton colors for implicit fixed points. Canonical
certificate keys are packed directly from the canonical word: an unoriented
cycle sends each occurrence to the preceding occurrence, and an oriented cycle
sends each rank to the next cyclic rank. This produces the same five-bit
algebraic images without decoding a canonical permutation and encoding it again.
Already established canonical representatives from direct generation retain a
cheap normalized-image path. Reconstruction state encoding uses the same writer
with its explicit fixed-point-free precondition; fixed points must be contracted
before admission. Certificate keys still support fixed points. Canonical key bytes are preserved. The active route, domains and explicit budgets
are versioned for TD20 and reject historical route checkpoints.

Root, endpoint and inverse certificate lookups construct these keys before
reading the database. A miss needs no materialized canonical pair. For a hit,
the checker reuses a normalized primitive input only after comparing its actual
images with the computed canonical key; otherwise it decodes the key into a
compact primitive pair for replay. Persisted keys do not establish canonicality
by themselves. Every hit still replays the complete witness, checks the project maximum and compares the
derived budget with the requested budget. That replay-derived budget is reused
instead of replaying the same witness twice.

Endpoint classes use the existing fixed-content twisted-bracelet generator and
Burnside counter. Both modules independently regenerate the complete annotated
cover. Elementary diameter bounds close whole families; every other class
requires a persisted complete witness, replayed diameter prefix, or final inverse
coverage. Direct root and endpoint generation emits one representative per
valid-pair class within each type. Ordinary emission therefore skips the
preliminary certificate lookup and searches the generated class directly.
Source imports and reconstruction retain certificate reuse. Reconstruction
replays each source batch with `multiGet`; CPU and GPU sorting consume its
misses without repeating per-class reads. Root prefetch follows the same exact
order: batched `multiGet`, parallel CPU prefixes for lookup misses, then a
bounded GPU queue containing CPU misses only. Every newly emitted result follows
the checked persistence path.

`PrimitiveInverseReconstruction` enumerates inverse gains zero through three on
both endpoints, restoring all deleted fixed symbols in all gaps. The emitter
and checker both use worker-owned image arrays and simultaneous gap choices,
with borrowed output arrays packed directly into version-three state keys.
The checker retains its independent permutation-multiplication, sequential
insertion and arbitrary-support implementation as a test oracle. Small-domain
tests compare exact labeled predecessor multisets, including multiplicities,
for every gain on both cycles; active-domain fixtures also compare class sets.
Within a fixed restored domain, inverse moves preserve the target because
`(omega * tau^-1) * (tau * beta) = omega * beta`; swapping endpoints has target
`omega^-1 * rho = beta`. Each inverse worker retains the known target in its
restored-domain arrays. Uncontracted source states are rejected, and every
accepted parent still passes actual forward
gain and pair replay. All gaps, gains and both endpoints remain covered.

`ErikssonAvoidance` performs three disk-backed waves: reverse gain two, reverse
both gains two/three, then reverse gains one/zero. Lookups and searches use each
state's explicit domain and budget; both after-one domains have budget 10.
Every final root uses 11. In both execution modes, `FinalInverseReconstruction`
checks the paper's support-family lemma before restoring each retained support
in every gap. `ImmediateRootClosure` replays a two-sided gain-at-least-two
closure on remaining parents before canonicalization. The persisted final set
contains exactly the roots outside these elementary closures. The independent
checker uses `FinalInverseCover`, with path/cycle minimum-cover formulas and
direct block exchange rather than the emitter's cover recursion and successor
traversal. All source classes remain mandatory; there is no budget-ten lookup.
Version-four ROOTS checkpoints identify `rootInventory=ELEMENTARY_RESIDUALS`;
they retain their original `FULL_SEARCH` or `RESIDUAL_EXPANSION` mode. Fresh
emission and residual resumes therefore share the same filtered final cover.
Production passes `writer::flush` to the reconstruction persistence barrier
before downstream checkpoints are published. A failure leaves the previous
completed source boundary intact and prevents a new checkpoint.
checker reports use `inverse-coverage-v3.txt` and account for every source and
support/gap slot. CPU sorting prefixes and intermediate GPU searches
are heuristics: a miss, depth limit or timeout preserves every inverse obligation.
CPU prefix workers, GPU dispatch workers and CPU inverse workers are independent.
A versioned disk queue feeds GPU searches; a second queues every GPU miss for
inverse generation. GPU workers never execute inverse callbacks or wait on their
persistence queues. Both handoffs have fixed-size validated records and exact
final counts. All pools join before sealing, with coverage equalities checking
source classification, every GPU result, and every transferred inverse case.
Progress reports count processed source cases against the full wave total and
show the remaining CPU, GPU and inverse work separately. The completed
budget-ten set is counted before final inverse generation starts: after writers
drain, `DiskStateSet.countKeys()` traverses RocksDB's merged logical keys with
independent iterators over disjoint half-open byte ranges on one snapshot.
The range counts sum exactly; overlapping SSTs are merged within each iterator.
Workers default to all available CPUs, and approximate range byte sizes guide
the bounded task partition without determining any inventory count.
Persisted canonical keys already identify distinct classes, so this
pass counts without decoding states, performing validation, invoking prefetch,
or constructing a Java deduplication set. The exact source count is saved in
the `AFTER_ONE` checkpoint. Final expansion reports completed expanded source
classes as `processed=X/N (P%)`, where `N` is this exact fixed source total
and `P=100 X/N` for a nonempty source. Remaining source classes, throughput
and an ETA estimated from the observed rate accompany that fraction. Output
key-occurrence and write counters are reported separately.
Intermediate CUDA searches explore the full obligation budget (9 or 10).
The former depth-five cap and its override have been removed.
`td20.reconstructionGpuMillis` defaults to 0 (no time cutoff). Budget-11 searches
always use full exploration depth 11 without a time cutoff.
Persistence is bounded and asynchronous. The checker
regenerates all waves from actual missing endpoint discharges, so missing
obstruction markers cannot suppress work.

CUDA's sorting tree excludes fixed symbols from supports, permits increases of
zero or two in the number of odd-length cycles, and additionally excludes
supports in three distinct algebraic cycles. The CPU prefix search permits
the latter when their odd-length-cycle increase is zero. A GPU search without
a time cutoff and with full exploration depth exhausts only this restricted
tree within its move budget.
Only replayed witnesses discharge a class; exhaustion, depth limits and timeouts all leave
it unresolved.

The kernel also skips a positional transposition `(i,j,k)` immediately followed
by `(i,i+k-j,k)`. These two segment interchanges return to the identical
cyclic-target pair. Deleting the adjacent inverse moves preserves any later
closure and saves two moves, so the skip preserves existence of a certificate
within the obligation budget.

Each CUDA warp keeps its parent state in shared memory and cooperatively
copies child batches into its locked queue. Every copying lane fences its
writes before the owner publishes queue metadata; all children are counted
before publication. Stop and completion polls use device-scoped relaxed
loads, with an explicit PTX mapping on compute capability 7.0 and later.
They do not publish queue entries or witness payloads.

Each search slot also holds a bounded cache of complete algebraic permutation
keys and the least depth visited. The target rho is fixed within that search,
so omega determines beta as a cycle, up to its starting position. A shallower
occurrence has at least as much remaining budget and exploration depth. The
only history restriction skips an immediate inverse edge to an already visited
parent; erasing that adjacent inverse pair preserves any successful continuation.
The cache compares all key bits under fenced locks. Hash collisions and full
probe sequences fall back to ordinary exploration. It is cleared between
searches, and a duplicate removal decrements the outstanding-work count.
Its 524,288 entries use 12 MiB per slot in addition to the configured queues.
The maintained default GPU configuration allocates 8 times 768 MiB, or 6 GiB, for queues.
Its 8 caches add 96 MiB. Memory preflight uses the allocator's
queue capacities and includes all device buffers: queues, caches, support
tables, input vectors and result metadata, before allocating any slot.

At an exploration cap, the consecutive-support scan first tests all moves
gaining at least two fixed symbols. If neither gain zero nor gain one can
close with the actual remaining obligation budget and known-diameter table,
general support enumeration is unnecessary: no child can be expanded and
no untested support can close. The result remains unresolved when the scan
fails. Cycle metadata is built only for nodes needing general enumeration.
This changes work per node without changing the represented search tree,
the exploration cap, or any closure budget.

The paper's capped all-odd lemma supplies an additional subtree rejection.
When the current 3-norm equals the remaining full budget, a successful
continuation has no zero moves available (zero means no change in the
odd-length-cycle count). If all cycles have odd length, every remaining
rank-decreasing move preserves that property. The kernel checks whether the
exploration cap can reach any rank at which the actual known-diameter table
permits an all-odd remainder to close. If it cannot, the subtree is unresolved.
Fixed points count as odd-length cycles; an even-length cycle disables this
rejection. Full-depth final-root searches are unaffected by this test.

Inverse generation batches up to 65,536 canonicalized keys per producer across
successive callbacks and
partitions them by a deterministic hash into independent writer shards.
Each shard has a lock-free queue and a single owner of its deduplication
buffer and sorted SST files. Producers never acquire a shared insertion
monitor or perform disk writes. Semaphores bound queued batches; producers
wait only when their destination queue is full. The default uses up to eight
writers, a 16-million-key aggregate deduplication window and 1,024 queued
batches, distributed across shards. All database writers honor
`td20.dbWriteBatchEntries` (524,288 entries by default); an inverse writer
flushes at the smaller of that limit and its share of the deduplication buffer.
Certificate and independent checker-state writers use the same property for
their RocksDB `WriteBatch` entry limits. Each inverse flush sorts distinct keys in unsigned
byte order, builds an SST outside the shared database write path, then ingests
it with global sequence numbers enabled. Duplicate keys across tables have
the same empty value and remain one set entry. SST construction bypasses WAL
and memtables; ingestion and compaction still require database coordination.
RocksDB allows up to sixteen background jobs and eight subcompactions.
Producers first publish to every shard with free space before waiting on a
full shard, so one slow destination cannot prevent all other writers receiving
the same batch's remaining partitions.

Producer buffers persist across callbacks on the same thread. Final partial
batches are queued after all generators join, before writer drain and sealing.
An atomic admission counter prevents sealing or closing while a generator
is active. After every generator joins, persistence stops admissions and drains
and joins every writer. The budget-ten boundary then uses the key-only counting
pass; existing validated sealing and the mandatory processing scans remain
available for their own obligations.
Generation or writer failure prevents sealing and propagates to blocked
producers. Native resources outlive every writer. The deterministic key
format and the represented class set are unchanged.
During final generation, thread-safe accumulated counters report generated,
submitted and consumed key occurrences, successfully ingested SST key
occurrences, and ingested table totals. These counters can include the same
canonical key more than once across batches or SSTs; they measure accumulated
output and persistence work. The exact distinct final-root count remains
unknown until the mandatory merged-database root scan completes. No per-write
global key lookup or extra deduplication pass is introduced.

During the final inverse wave, encoded root batches also feed a bounded
advisory prefetch pipeline. Preparation workers perform batched `multiGet`;
lookup misses pass through all configured CPU-prefix workers, and only CPU
misses enter the GPU queue. GPU searches use a positive time slice (100 ms
by default), filling gaps between source batches without indefinitely holding
GPU slots needed by source checks. Early root searches overlap continued
inverse generation and SST persistence. Queue pressure never blocks
generation; recent-key suppression and discarded advisory work cannot omit
obligations. Started searches finish and use the normal checked certificate
path. The sealed root inventory is still exhaustively scanned afterwards.
Prefetch reports handled occurrences, `submittedSoFar` and pending work. Its
submitted total grows during generation, so it carries no overall expansion
completion percentage. Final-wave completion is reported against the fixed
counted source inventory instead.

Sealed checkpoints are versioned computation state. The latest-wave count,
route and budgets are validated on resume. Intrinsic certificates remain
route-independent and are replayed at the requested budget. Counts, successful
partial scopes, checkpoints and passing tests are distinct from a complete
end-to-end proof report.
Legacy version-three `AFTER_ONE` checkpoints may contain `afterOne=-1` for a
completed residual wave. Resume counts that persisted set with `countKeys()`
and atomically upgrades the checkpoint before activating final-root prefetch
or starting final inverse generation. New completed budget-ten checkpoints
record the count immediately.
