# Architecture

[The source specification](td20-reduction-count.tex) is the mathematical
contract. The system keeps four boundaries explicit: inventory and class
generation, exact counting, sorting search, and independent verification.

## Mathematical core

A cyclic-target candidate is unchecked until its current cycle, even
algebraic permutation, and full-cycle target are validated. The general pair
model retains fixed points. Fixed-content generators accept only contracted,
fixed-point-free pairs. Candidate orbit keys and valid-pair class keys are
different immutable types.

The joint package owns the 21-symbol root constants, budget 11, the 83-row
partition inventory, the independently generated 104 endpoint annotations,
and the joint-3 routing predicates. The route tests immediate gains of at
least two; every gain-one first move; every gain-one, gain-two, or gain-three
second move relevant to the route; and all required third moves. Structural
descent stops at every totally unoriented state.

The fixed-content bracelet generator handles totally unoriented roots and
annotated endpoints. The constrained root generator assigns current-cycle
positions to canonical algebraic labels and uses only sound partial-position
pruning. Every surviving leaf is replayed against the full joint-3 predicate.
Both generators enforce auxiliary standardization, the twisted dihedral
action, and full-target realizability before emitting a class.

## Counting

The totally unoriented count uses Burnside's identity independently of the
joint-3 predicates. The Java joint generator enumerates oriented residuals and
classifies the explicit support-clearing powers. Endpoint Burnside counting is
a third artifact. The summary layer requires all 83 deterministic root keys,
all 104 endpoint keys when supplied, and the identity `J = C + O` in every
row. A count record never doubles as a sorting certificate.

The machinery follows TD(18): bracelet generation builds disjoint prefix
subtrees, Burnside counting submits independent support and symmetry tasks,
and joint generation uses the same bounded worker pools and prefix depths.
Snapshots own mutable arrays and share immutable tables. Producer scopes join
every worker before releasing resources. Partition counting uses a fixed pool
sized from the processors visible to the JVM. The emitter retains TD(18)
worker detection, including the GPU-slot allowance for bracelet and inverse
work; the checker uses its CPU worker setting. Historical native and adaptive
Java count rows retain their original implementation provenance on resumption.

## Emitter

The emitter sends every direct root to sorting with budget 11 unless an
explicit support-clearing sequence already discharges it. Endpoints use budget
8. Complete witnesses and known-diameter closures have separate record kinds
and are replayed before persistence. CPU prefixes and CUDA scheduling are
search heuristics: a miss preserves the original class and full budget.

Endpoint failures enter tagged inverse coverage. The first inverse wave
reverses the third move and creates 19-symbol `(1,1,3)` or 18-symbol
`(1,2,2)` predecessors with budget 9. The second reverses the tagged middle
move and creates 20-symbol predecessors with budget 10. The third reverses the
gain-one first move and creates 21-symbol roots with budget 11. Each wave:

- enumerates every gap distribution and compatible support;
- verifies the actual forward gain and odd-cycle increase;
- canonicalizes by `ExtendedToricClassKey`;
- deduplicates on disk while retaining the union of route tags; and
- propagates only classes without a checked result at that wave's budget.

Bounded queues and batched certificate reads control memory. CPU worker counts
default to the processors available to the JVM. CUDA initializes only for a
sorting search that falls through the CPU checks.

## Checker

The checker has no dependency on the emitter artifact. It owns independent
partition generation, joint-3 replay, endpoint generation, all three inverse
waves, and certificate replay. Source counts are accepted only after the
checker regenerates the exact inventory. Every endpoint missing an eight-move
result enters inverse coverage even if no obstruction marker exists.

Certificate lookups may be batched and replay may be parallel, but each record
is decoded, applied to its represented pair, and checked against the requested
budget. A longer witness cannot close a smaller budget. Missing, malformed, or
incorrect records remain unresolved obligations.

## Persistence

The certificate database has a schema marker and separate data for sorting
results and operational obstruction markers. Canonical instance keys use five
bits per symbol. A sorting-result value records its kind, exact move count, and
transposition triples. The accepted maximum is 11 moves. Complete records must
reach identity; diameter-closure records must leave a contracted domain whose
independently tabulated diameter fits the unused budget.

Operational work databases and sealed wave checkpoints accelerate resumption,
but they are not proof inputs. The checker regenerates obligations from the
mathematical inventories. Generated databases, count artifacts, work sets,
checkpoints, and logs remain untracked.
