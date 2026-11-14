# Active proof completion for TD(20)

The objective is a checked upper bound of 11; the reverse-permutation
lower bound is 11. The [source paper](td20-reduction-count.tex)
defines the active two-sided Eriksson-plus-third-move route. TD(18) = 10 is the explicit completed premise supplied for this project; the TD18 repository is not asserted to have completed its proof.
Counts and passing tests alone do not establish the upper bound.

## 1. Coverage and small domains

Check the source paper's endpoint-side triangle inequality, fixed-point
contraction, exhaustive gain-zero/one first supports, gain-at-least-two
characterization, reverse exception and explicit budget-valid support patterns.
The two inverse branches are `(1,2,2)` and `(0,3,2)`, on both endpoints.
The former first-move qualifying restriction is not an active premise.

Exit artifacts: the source coverage derivation and the passing
`ActiveErikssonRouteTest` and `ActiveErikssonInverseTest` suites in both modules.
The tests compare complete small-domain class sets and all inverse gains zero
through three against arbitrary full-cycle support enumeration.

## 2. Independent active census

Regenerate all 83 root partitions in both modules. The complete emitter census retains 0 unoriented and 8 oriented classes; 2 are support-cleared, leaving **6 direct root classes**. Independent full-domain regeneration agrees on every partition.
All 143 annotated endpoint types must be regenerated structurally; each
non-elementary generated class count is independently checked against Burnside.
The endpoint cover contains all 143 even fixed-point-free annotated types on 16 symbols, with budget 8.

Exit artifacts: every versioned report in `data/eriksson-root-counts-v1/` and
`data/eriksson-root-counts-checker-v1/`, matching the active paper rows, and the
complete structural endpoint cover. Endpoint census reports, where counted,
are under the corresponding `eriksson-endpoint-counts[-checker]-v1` directories.
All pending, missing, incompatible or malformed required data must be rejected.

## 3. Sorting obligations

Each active direct class needs a persisted complete witness or valid diameter
closure within 11 moves. Each non-elementary endpoint needs a checked
result within 8 moves or regenerated inverse coverage. Intrinsic results are
replayed at the explicit requested budget. A longer valid witness is a miss
for a smaller budget; malformed data are errors. A bounded CPU or GPU miss is
not a lower bound and cannot close a class.

Exit artifacts: replayable results in the certificate database for every direct
class, and endpoint discharges or the full regenerated residual set. No sorting
failure count is assumed in advance.

## 4. Complete inverse coverage

Reverse the third gain-two move, then both possible second moves, then the
corresponding first move. The after-two budget is 9; the after-one
budget is 10; every remaining root needs budget 11. Restore all
fixed symbols in all gaps, reverse every compatible support on both endpoints,
and verify actual forward gains. Use disk-backed class deduplication and bounded
persistence. Missing obstruction markers cannot omit any obligation.

Exit artifact: a complete independently regenerated inverse report with no
unresolved root. If all endpoints have diameter closures, its residual set and
all inverse waves are empty; the full checker must still report that scope.

## 5. End-to-end verification

Run `tools/td20 verify --phase=all` against the completed database, with no
range omissions and fresh checker work. It regenerates all roots and endpoint
obligations, replays witnesses, and independently rebuilds the three inverse
waves. Emitter checkpoints, checksums and sidecar manifests are not proof inputs.

Exit artifacts: both full required Maven CPU suites and one successful complete
checker report, together with the counted inventories and replayed database.
Only when all five criteria have checked artifacts may TD(20) = 11
be reported as established by this program. CUDA tests require a configured
CUDA environment; a proof needing only CPU certificates does not require GPU use.
