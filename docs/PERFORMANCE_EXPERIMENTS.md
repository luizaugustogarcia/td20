# Primitive representations and emitter benchmark

The previous emitter converted each accepted inverse state from image arrays
into MulticyclePermutation and Cycle objects, canonicalizes an encoding,
decodes another canonical pair, then packs its images as a certificate key.
Repeated cycle decomposition, hash sets, identity maps and temporary arrays
make this a strong performance candidate. Code inspection does not establish
its fraction of the production runtime.

The primitive algorithms have now been integrated into production generation,
reconstruction, sorting, certificate replay, import, merging, and fleet state
preparation. Immutable byte vectors own state at asynchronous boundaries; each
worker owns reusable computation arrays. Inverse output packs one versioned
canonical key allocation directly into bounded persistence batches. Packed
transposition triples replace cycle objects in witness processing. Production
entry points select primitive APIs; legacy adapters remain for general APIs
and comparison references. Domains remain at most 21 symbols.

The independent checker, budgets, exhaustive branches, scheduling, state
version 3, certificate version 2, and WAL-disabled persistence are preserved.
This intentionally changes the emitter representation compared with TD18;
it does not change the mathematical obligations or establish the pending
TD20 upper bound. Reconstruction was stopped for migration and verification.

## Checker migration

The checker now mirrors the emitter primitive representation from commit
`387910b`: immutable `PrimitivePair` byte vectors, worker-owned
`PrimitiveCanonicalizer` and inverse arrays, packed `PrimitiveCertificate`
transpositions, and direct canonical-key packing into bounded inverse
persistence batches. Its root and endpoint generators retain their checker
recurrences and emit compact states. `FinalInverseCover` retains the independent
family predicate and forward replay, and sends borrowed residual arrays to the
same persistence boundary. The checker module has no emitter dependency.

The previous checker permutation APIs remain comparison oracles. Persisted keys
are decoded without granting canonicality; the import audit recomputes their
canonical keys and rejects mismatches. Batched proof lookup computes keys from
regenerated obligations, replays every hit in that canonical frame, rejects
malformed records even when their move counts exceed the requested budget, and
preserves missing witnesses as obligations. State version three, certificate
version two, budgets 8/9/10/11, final coverage reports and WAL-disabled
persistence remain unchanged.

Checker validation compares canonical keys with the legacy orbit oracle for
every valid normalized pair through seven symbols; inverse labeled multisets
for gains zero through three through six-symbol sources; active inverse
fixtures through 21 symbols; direct generation classes and counts; all packed
witness boundaries; and arbitrary multi-move replay and prefix budgets across
domains three through 21. These are bounded equivalence checks. The emitter
benchmark numbers below do not measure checker speed or complete proof coverage.

Migration validation passed: `mvn -pl checker -am test` ran 130 tests with no
failures, errors or skips; `mvn -pl emitter -am test` ran 301 tests with no
failures or errors and 16 configured CUDA skips. The JShell structural inventory
check confirmed all 83 root partitions and 143 endpoint annotations. No checker
speed benchmark or full proof-verification run was performed.

## Representation and equivalence

Each worker owns reusable primitive successor, inverse, current-order, position,
cycle-label and rank arrays. Enumeration changes three algebraic images and
restores them after each callback. Integer masks replace visited sets. Restored
fixed symbols all belong to the inverse support; since the child was contracted,
no other image can become fixed. Accepted parents still replay the actual
forward gain, all changed images and every current-cycle edge. Both endpoints
and every gap/support are enumerated. Endpoint interchange remains a separate
reconstruction branch, not extra class equivalence.

PrimitiveCanonicalizer preserves the fixed-content alphabet order used by
TwistedBraceletKey: decreasing cycle size, then unoriented before oriented,
then first occurrence among indistinguishable colors. An unoriented cycle
follows inverse current order on its support. Singletons encode fixed points;
algebraic 2-cycles are unranked. Oriented ranks follow algebraic successors
and are standardized from the first occurrence in each orbit image.

Let p be an original position and s a shift. Rotation relabels positions by
q(p) = p - s modulo N. Reflection uses q(p) = -s - p modulo N and inverts
oriented ranks. After identifying symbols with current-cycle positions, the
normalized algebraic permutation is q * omega * q inverse for a rotation,
and q * omega inverse * q inverse for a reflected image. The writer packs
these images directly in the existing five-bit, most-significant-bit-first
format. It constructs neither an encoding word nor a canonical pair.

The public array API validates domain bijections and full target-cycle
realizability in linear time. The inverse callback may use the already-proved
pair boundary after exact replay. Borrowed arrays must not leave a synchronous
callback. Each emitted packed key is a fresh byte array; an asynchronous
consumer must take ownership and never mutate it.

The tests compare all 874 valid normalized pairs through seven symbols with
the existing canonical key bytes and random full-target pairs on every domain
1..21. They also check fixed points, arbitrary relabeling, workspace reuse,
invalid inputs and key decoding. Inverse tests compare exact ordered outputs
and multiplicity for every fixed-point-free valid child through six symbols
and every gain 0..3. Four 18/20/21-symbol fixtures compare complete canonical
class sets. Callback failures and reentrant workspace use are tested.

## Bounded benchmark

The benchmark has four source classes, retaining the production symbol lengths:
18 symbols with inverse gains two and three, 20 with gain one, and 21 with
gain zero. Their algebraic images are shifts by 4, 4, 2 and 1 respectively
with the normalized current cycle. They are synthetic fixtures.

Inverse enumeration is measured for each single source class. Canonical key
creation is measured on at most 16 distinct emitted classes per case, verified
individually against the existing implementation. Sample acquisition stops
once that population is reached. The benchmark does not claim exhaustive
class-set verification; the separate correctness tests cover complete sets.

The harness uses two warmups and three measured repetitions, alternating
implementation order. It reports median wall time, current-thread CPU time
and allocated bytes per output. One-time workspace and sample construction
are outside the timed section. The key test starts with primitive arrays:
the existing path materializes and canonicalizes a pair, while the experiment
packs the already-proved canonical frame directly. Neither uses a GPU or
writes RocksDB.

Launch tools/bench/PrimitivePermutations.jsh through JShell using the isolated
build's emitter classpath. The experiment is available through
tools/td20 bench-permutations after building that source copy. Set
TD20_JSHELL_OPTIONS to -R-XX:ActiveProcessorCount=1 for the benchmark JVM.
While reconstruction is running, build and test in a temporary source copy;
do not rebuild its active target/classes or target/native.

A live comparison shares the machine with reconstruction. Wall times depend
on scheduling. Per-thread CPU time and allocation distinguish implementation
work from waiting, but a four-class sample establishes neither a production
speedup nor a completed proof artifact. Generated logs remain untracked.

## Initial live comparison, 18 November 2026

The isolated JDK 21.0.4 run used one CPU core while production reconstruction
continued. Each of the four cases had 16 sampled canonical classes, for 64
total. All sampled keys and raw output counts/digests agreed. The seven focused
correctness tests also passed, including complete full-length class sets.

| Source symbols | Inverse gain | Raw outputs | Inverse CPU speedup | Key CPU speedup | Existing bytes/key | Primitive bytes/key |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 18 | 2 | 6,156 | 61.04x | 21.48x | 14,812.5 | 36.5 |
| 18 | 3 | 2,280 | 22.75x | 9.69x | 15,756.5 | 36.5 |
| 20 | 1 | 6,960 | 20.78x | 9.89x | 15,085.0 | 36.5 |
| 21 | 0 | 2,093 | 28.34x | 4.75x | 15,562.0 | 36.5 |

Existing inverse enumeration allocated 7,530..8,179 bytes per raw output.
The primitive inverse workspace allocated under one byte per output amortized
over a source call; its support loop allocates nothing. Key allocation includes
small amortized benchmark/measurement overhead as well as the packed byte array.
These deliberately tiny timings have limited JIT warmup and share CPU/cache
resources with production. They establish a promising implementation comparison,
not the production bottleneck percentage or a forecast for total reconstruction
time. No live reconstruction implementation was replaced.

After the operator stopped reconstruction, the same bounded benchmark was
repeated with total-duration reporting. It finished in 1.703 seconds, excluding
JShell startup. Every sampled key and measured output still agreed; allocations
were unchanged. Median per-thread CPU speedups were:

| Source symbols | Inverse gain | Inverse CPU speedup | Key CPU speedup |
| ---: | ---: | ---: | ---: |
| 18 | 2 | 12.01x | 2.94x |
| 18 | 3 | 7.75x | 6.29x |
| 20 | 1 | 11.35x | 5.80x |
| 21 | 0 | 11.31x | 4.72x |

The timing variation between these tiny runs is a reason to treat speed ratios
as preliminary. The stable allocation difference and exact output agreement
are stronger evidence for continuing with the specialized representation.

The required CPU suites passed in the isolated temporary Git checkout with
normal CPU detection: emitter ran 252 tests with zero failures/errors and
15 GPU tests skipped; checker ran 105 with zero failures/errors and no skips.
The initial single-core source-archive validation environment was unsuitable
for an existing parallel-scan test and a Git-dependent fleet test; rerunning
in a real temporary Git checkout with normal workers resolved both environment
failures. No production source was changed to accommodate those tests.

## Production migration verification, 18 November 2026

After integration, `mvn -pl emitter -am test` reported 280 tests with zero
failures/errors and 16 gated CUDA tests skipped. `mvn -pl checker -am test`
passed all 105 tests with no skips. These include exhaustive small-domain
inverse and canonical-key comparisons, full-length class-set fixtures,
byte-identical state/certificate persistence, batched imports, CPU-before-GPU
ordering, and completed-wave resume.

The separately enabled Maven `GPUSortingSearchSmokeTest` passed both tests
on the local RTX 4070 Laptop GPU using the existing native library. It checks
concurrent bounded dispatch and a 21-symbol primitive state with an arbitrary
current-cycle anchor, then replays the returned packed witness. This is a
bridge smoke check, not an exhaustive CUDA validation or production throughput
measurement. No larger benchmark was run for this migration.
