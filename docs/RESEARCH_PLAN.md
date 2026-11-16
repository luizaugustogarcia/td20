# Research plan and proof completion

The objective is a checked upper bound of eleven transpositions for every
ordinary order-20 instance. Together with the established reverse-permutation
lower bound TD(20) >= 11, this would establish TD(20) = 11. The upper bound is
**not yet proved**. [The source paper](td20-reduction-count.tex) specifies the
two-sided Eriksson-plus-third-move route. TD(18) = 10 is an explicit established
premise, alongside the separately certified TD(16) = 9 and smaller bounds.

The user authorized replacement of joint-2 on 16 November 2026. Its completed
U/J/C/O/R counts and 214-annotation endpoint census describe a superseded route;
they cannot supply counts or sorting coverage for the active route.

## 1. Check mathematical coverage and small domains

Validate cyclic-target conventions, fixed-point contraction, fixed-content
encoding, auxiliary standardization, twisted reflection with rank inversion,
orbit correspondence, and the independently established diameter premises.
Check the endpoint-side triangle inequality and reverse exception. Enumerate
every gain-zero/one first support on both endpoints, every second move producing
three fixed symbols, and every third move creating at least two fixed symbols.
Two moves producing four fixed symbols or three producing six close by known
bounds. No qualifying odd-cycle restriction applies to the first move.

Verify the consecutive-triple characterization of gain-at-least-two moves,
including even-length algebraic cycles. Check the explicit budget-valid support
patterns at powers 9:7, 11:6, 13:7/10/11 and 17:9/13/15. No unlisted clearing is
assumed. Check both inverse branches `(1,2,2)` and `(0,3,2)` on both endpoints.

Exit criterion: a checked coverage derivation agreeing with the paper and both
implementations, and passing complete small-domain class-set and arbitrary-support
forward/inverse oracles, including gains zero through three.

## 2. Independently regenerate counts and generation

Regenerate all 83 fixed-point-free even root partitions of 21 and all 143
orientation annotations from the 28 even endpoint partitions of 16. Algebraic
2-cycles are unoriented. Every endpoint annotation is included without a stricter
3-norm cutoff, including types with zero realizable classes. The endpoint census is complete and agrees between both modules in every row:
14,098,353,328 classes, with 11,523,939,269 outside elementary closures.
The paper's pending root rows support structural checks only.

For every root partition, independently count retained unoriented roots U3,
retained oriented roots O3, and the support-cleared subset C3. Direct obligations
total U3 + O3 - C3. Both modules must agree in every row. Endpoint counts require
independent Burnside/direct agreement on the full generated scope. Counting
alone discharges no sorting obligation.

Exit criterion: complete independently checked versioned reports under
`data/eriksson-root-counts-v1/`, `data/eriksson-root-counts-checker-v1/`,
`data/eriksson-endpoint-counts-v1/` and
`data/eriksson-endpoint-counts-checker-v1/`, matching all active paper rows.
Keys and iteration must be deterministic; pending, missing, duplicate,
malformed, incompatible and out-of-scope records must be rejected.

## 3. Discharge direct roots and endpoints

Every direct root requires a persisted complete witness or checked diameter
closure within eleven moves. Every non-elementary endpoint requires a result
within eight moves or full regenerated inverse coverage. Reused witnesses must
be replayed at the requested budget; a partial source database never closes a
type by its reported count. A valid longer witness is a miss for a smaller
budget; malformed data are errors.

A diameter prefix must replay applicable transpositions, preserve its search
target, contract resulting fixed points, and satisfy the actual remaining known
bound. CPU prefix and CUDA limits leave the original obligation intact. Neither
exhaustion of the restricted CUDA tree nor a timeout proves a lower bound.

Exit criterion: replayable results for all direct roots, and endpoint discharges
or the complete regenerated endpoint residual set. No predicted failure list
or count is a premise.

## 4. Complete inverse coverage

For each residual 16-symbol endpoint, reverse the third gain-two move by restoring
two fixed symbols in all gaps and testing every compatible support on both
endpoints. The resulting 18-symbol after-two states have budget nine.

For every unclosed state, reverse a gain-two second move to obtain 20 symbols
with budget ten, or a gain-three second move to obtain 21 symbols with budget
ten. Reverse respectively the gain-one first move by restoring one fixed symbol,
or every gain-zero first support without restoring a symbol. Every accepted
parent must pass actual forward-gain and pair replay. No odd-cycle qualifying
restriction may remove a gain-zero/one first move.

Deduplicate valid-pair classes on disk with bounded persistence. Every final
21-symbol root requires budget-eleven closure or a complete witness. Missing
obstruction markers, interrupted queues, advisory prefetch misses and evicted
cache entries cannot omit a class.

Exit criterion: an independently regenerated complete three-wave report with
counts and no unresolved final root, covering both branches and both endpoints.
If a wave is empty, the full checker must still report that scope.

## 5. Independently verify the entire proof

Run `tools/td20 verify --phase=all` against the finished certificate database
with fresh checker work and no range omissions. It must independently regenerate
inventories, roots, endpoint classes and inverse waves; enforce every count and
budget; replay all required witnesses; and reject missing results. Emitter
checkpoints, checksums and sidecar manifests are not proof inputs.

Exit criterion: both required Maven CPU suites and one complete successful
end-to-end checker report, together with every counted inventory, replayed
database and cited mathematical premise. CUDA-dependent tests require a configured
environment; a proof using only CPU certificates need not use CUDA.

All five criteria require checked artifacts. Structural regeneration and tests
alone do not establish TD(20) = 11. Full active-route counts, sorting and the
complete checker report remain pending until their actual artifacts exist.
Generated databases, checkpoints, reports, certificates and logs remain untracked.
Record completed execution metadata and artifact locations in
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md); see
[VALIDATION.md](VALIDATION.md) for the procedure.
