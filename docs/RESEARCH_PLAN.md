# Research plan and proof completion

The objective is a checked proof that TD(20) = 11. The established
reverse-permutation lower bound is TD(20) >= 11; see the
[shared paper](../../td/docs/paper/main.tex). TD(18) = 10 is an independently
checked established premise. The remaining direction is TD(20) <= 11.
No count supplies a sorting certificate.

## 1. Mathematical coverage

Validate cyclic-target pairs, contraction, twisted class correspondence, and
diameter premises. Independently regenerate all 83 even fixed-point-free
partitions of 21 and the 214 endpoint annotations on 18 symbols with 3-norm
at most eight, comprising 30 underlying partitions.

Joint-2 tests every immediate gain at least two. Otherwise inspect every
qualifying gain-one first move followed by every gain-two or gain-three second
move. Gain three closes; gain two produces a budget-nine endpoint. Totally
unoriented roots with only odd-length cycles have no qualifying first move;
test those with even-length cycles. Totally unoriented successors cannot supply
the required second gain. Two moves creating only two fixed points are excluded. Include
supports within even-length cycles and across cycles.

Exit criterion: an emitter-independent coverage derivation, exhaustive
small-domain class-set agreement, and complete two-wave inverse construction.

## 2. Class counts

Every root partition records totally unoriented classes U, oriented joint-2
residuals J, clearing subset C, and O = J - C. Let R count totally unoriented
roots without a favorable prefix. Direct roots total R + O. Every paper
partition supplies an explicit R count or pending row; missing data never means zero.
Only powers 9:7, 11:6, 13:7/10/11, and 17:9/13/15 are admitted.
Retain the selection-independent U count. Route-dependent counts require
fresh artifacts; missing values remain explicitly pending.

Burnside and generation preserve fixed content, auxiliary standardization,
twisted reflection with rank inversion, and full-target realizability.
Versioned artifacts contain all deterministic keys and reject missing or
duplicate rows and superseded route formats.

Exit criterion: complete validated count artifacts, independent small-domain
class-set equality, and source tables agreeing row for row. Root emission
and proof verification reject pending counts. Production checking independently
regenerates every represented class.


All 83 Java R counts are complete: R = 124,395,153 and R + O = 2,146,972,300.
Joint-2 removes 12,579,718,908 former direct unoriented obligations, or 99.02%
of U. Independent arbitrary-support regeneration agrees in all 83 rows. Versioned
partition artifacts are in data/unoriented-joint-residuals-v1/ and
data/unoriented-joint-residuals-checker-v1/. No count discharges a witness or
an inverse obligation.

## 3. Direct roots and endpoints

Direct roots require budget-eleven results. Every class in the 214-type
18-symbol cover requires a budget-nine discharge or inverse coverage.
The 104 annotations containing an oriented 3-cycle close by one move plus
TD(14) = 8. The other 110 annotations are generated in full. No endpoint
failure count is a premise.

Exit criterion: every root has a replayed witness or valid closure; every
regenerated endpoint is discharged or retained for inverse coverage.

## 4. Two-wave inverse coverage

Restore two fixed points and reverse gain two, producing 20-symbol budget-ten
predecessors. Restore one fixed point and reverse gain one, producing
21-symbol budget-eleven roots. Enumerate all cyclic gap distributions and
compatible supports. Require exact forward gain, odd-cycle increase two,
valid pairs and fixed-point-free predecessors. Only the gain-two intermediate
must be non-totally-unoriented; the gain-one inverse wave admits roots of either
orientation, including totally unoriented roots with even-length cycles.

Exit criterion: complete independently regenerated reports for both waves,
replayed discharges, and no unresolved root. Checkpoints assist execution;
they are not proof inputs.

## 5. Complete proof verification

The CPU checker regenerates all obligations from the certificate database.
Missing markers, checksums, and sidecars cannot omit work.

Exit criterion: one full successful report covering all 83 root rows, all 214
endpoint annotations, both inverse waves, all required results, and the
stated premises. Only then does TD(20) <= 11 follow. Combining the checked
upper bound with the established lower bound proves TD(20) = 11.

Generated artifacts remain untracked. Partial scopes and small-domain checks
do not fulfill these production exit criteria.
