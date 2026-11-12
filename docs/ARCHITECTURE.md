# Architecture

Joint-2 includes totally unoriented roots with even-length cycles. All-odd
totally unoriented roots and all totally unoriented successors stop for the
reasons proved in the source paper. The active direct count is R + O; R counts
unoriented residuals. All 83 R counts are complete, with R = 124,395,153. See
[WORKFLOW.md](WORKFLOW.md) for Java counting and the versioned artifact formats.

[The source specification](td20-reduction-count.tex) defines coverage.
Emitter and checker are separate Maven modules; the checker has no emitter
artifact dependency. Their interface is the versioned certificate format,
deterministic class keys, and independently defined diameter premises.

## Models and generation

A cyclic-target pair is (omega, beta), with rho = omega * beta a full target
cycle. Candidates remain unchecked until conversion. Fixed points are kept
in the general model and contracted before fixed-point-free generation.
Fixed-content encoding identifies cycle supports and oriented ranks.
Auxiliary standardization renames equal annotated colors and shifts rank origins.
Twisted reflection reverses positions and inverts oriented cyclic ranks.
TwistedBraceletKey deduplicates candidate orbits; ExtendedToricClassKey identifies
valid-pair classes.

Joint-2 uses the TD(18) two-move rule on 21-symbol roots: immediate gain at
least two, otherwise qualifying gain one followed by gain two or three.
Totally unoriented all-odd roots stop; roots with even-length cycles participate.
Totally unoriented successors cannot supply the required second gain. Emitter
prefix predicates reject forbidden supports as soon as positions are known.
The checker derives predicates independently using arbitrary supports in both
orientations. Exhaustive small-domain replay compares complete class sets.

Burnside remains the TD(18) Java counter. U is independent of selection;
joint generation counts J and separates clearing subset C from O = J - C.
Endpoint counting covers 214 annotations. Persistence requires deterministic
keys and complete counts. Explicit pending rows support structural checks;
root emission and proof verification require completed numeric counts.

## Scheduling, search, and reconstruction

TD(18) worker detection, bounded queues, prefix scheduling, and asynchronous
persistence are reused. The existing adaptive emitter frontier is retained;
the checker keeps its independent frontier. CPU-prefix and CUDA move ordering
are heuristics: timeout or miss leaves the unchanged full-budget obligation.
Runtime native sources remain CUDA.

Roots use budget eleven; 18-symbol endpoints use nine. Two disk-backed waves
restore gain two on 20 symbols with budget ten, then gain one on 21 symbols
with budget eleven. Every gap and support is forward replayed. All paths use
one inverse recipe, so route tags are unnecessary. Sealed checkpoints record
operational counts and reject superseded versions.

## Verification and persistence

The checker regenerates roots, endpoints, both waves, and witness obligations.
Missing obstruction markers cannot omit work. Checkpoints, checksums, and
sidecars are not proof inputs. Complete records must reach identity. Diameter
closures replay a prefix and check the actual contracted remainder's bound.

The database schema is joint2-selection-v2 under td20-schema. Raw canonical
keys encode omega in five bits per symbol with standard beta implicit. Values
distinguish complete results and diameter closures and store at most eleven
transpositions. Unknown kinds, lengths, padding, incorrect replay, and missing
required results are rejected. Imports contribute individually replayed
witnesses; they supply neither coverage nor counts.

Generated artifacts remain untracked. [The research plan](RESEARCH_PLAN.md)
defines full proof completion.
