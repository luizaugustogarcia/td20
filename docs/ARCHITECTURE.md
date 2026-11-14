# Active route architecture

The [paper](td20-reduction-count.tex) specifies the mathematics and
[research plan](RESEARCH_PLAN.md) states proof completion criteria. The public
`WitnessEmitter` and `CertificateChecker` entries dispatch to their active
`ErikssonWitnessEmitter` and `ErikssonCertificateChecker` implementations.
The Maven modules remain independent: the checker has no emitter dependency.

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

Endpoint classes use the existing fixed-content twisted-bracelet generator and
Burnside counter. Both modules independently regenerate the complete annotated
cover. Elementary diameter bounds close whole families; every other class
requires a persisted complete witness, replayed diameter prefix, or final inverse
coverage.

`ErikssonInverseReconstruction` enumerates inverse gains zero through three on
both endpoints, restoring all deleted fixed symbols in all gaps. The emitter
uses image arrays and simultaneous gap choices. The checker uses permutation
multiplication and sequential insertion. Small-domain tests compare complete
predecessor class sets for every gain on both cycles.

`ErikssonAvoidance` performs three disk-backed waves: reverse gain two, reverse
both gains two/three, then reverse gains one/zero. Lookups and searches use each
state's explicit domain and budget; both after-one domains have budget 10.
Every final root uses 11. CPU sorting prefixes are heuristics before the
full GPU obligation. Persistence is bounded and asynchronous. The checker
regenerates all waves from actual missing endpoint discharges, so missing
obstruction markers cannot suppress work.

Sealed checkpoints are versioned computation state. The latest-wave count,
route and budgets are validated on resume. Intrinsic certificates remain
route-independent and are replayed at the requested budget. Counts, successful
partial scopes, checkpoints and passing tests are distinct from a complete
end-to-end proof report.
