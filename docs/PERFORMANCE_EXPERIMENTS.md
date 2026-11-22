# Primitive representations and emitter benchmark

The previous emitter converted each accepted inverse state from image arrays
into MulticyclePermutation and Cycle objects, canonicalizes an encoding,
decodes another canonical pair, then packs its images as a certificate key.
Repeated cycle decomposition, hash sets, identity maps and temporary arrays
make this a strong performance candidate. Code inspection does not establish
its fraction of the production runtime.

The primitive algorithms have now been integrated into production generation,
reconstruction, sorting, certificate replay, import, merging, and state
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
TD20 upper bound.

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
domains three through 21. These are bounded equivalence checks. Full independent proof verification remains pending.

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
