# Research plan and proof completion

The objective is a checked proof that TD(20) = 11. The reverse permutation has
transposition distance 11, so the established reverse-permutation bound gives
TD(20) >= 11; the [shared paper](../../td/docs/paper/main.tex) records the
formula and its original reference. This lower bound is independent of the
computation here. The computational program must prove the matching upper bound
TD(20) <= 11. The user-directed premise TD(18) = 10 is assumed. The equality
is not proved until every exit criterion below has a checked artifact, and no
class count supplies a sorting certificate.

## 1. Check mathematical coverage

Validate cyclic-target pairs, fixed-point contraction, the twisted class
correspondence, and the diameter premises. Independently regenerate all 83
fixed-point-free even partitions of 21 and all 104 annotated endpoint types on
16 symbols with 3-norm at most seven.

The exact joint-3 routing first accepts an immediate gain of at least two.
Otherwise it follows every qualifying gain-one first move. A gain-three second
move closes; gain-one and gain-two second moves continue. Favorable depth-three
gain sequences are (1,1,3), (1,2,2), and (1,2,3). Totally unoriented states
stop structural routing at every depth. Tests must include supports in
even-length cycles and across cycles.

Exit criterion: an emitter-independent coverage derivation, exhaustive
small-domain agreement, and complete three-wave inverse construction.

## 2. Check class counts

For every root partition record totally-unoriented classes U, oriented joint-3
residuals J, support-cleared residuals C, and O = J - C. The direct root count
is U + O. Use only the certified clearing powers 9:7, 11:6, 13:7/10/11, and
17:9/13/15.

Burnside counting for U and constrained generation for J must preserve fixed
content, auxiliary standardization, twisted reflection with oriented-rank
inversion, and full-target realizability. Versioned artifacts must include all
83 deterministic partition keys and reject missing or duplicate rows.

Exit criterion: complete versioned Java count totals, explicit provenance for
retained independent native audit rows, independent small-domain class-set
equality, and a source table agreeing row for row. Production verification must
regenerate every class independently. Counting does not supply sorting witnesses.

## 3. Discharge direct roots and endpoints

Every direct root needs a checked result within eleven moves. Each endpoint in
the 104-type, 16-symbol cover needs a checked result within eight moves or must
enter final inverse reconstruction. The 52 types with an oriented 3-cycle have
the elementary one-move closure. No endpoint-failure count is a premise.

Exit criterion: every regenerated direct root and endpoint has a replayed
complete witness, a verified known-diameter closure, or a retained inverse
obligation.

## 4. Complete three-wave inverse coverage

Reverse the third gain according to the retained tag: gain three for (1,1,3)
or gain two for (1,2,2). This produces budget-nine predecessors on 19 or 18
symbols. Reverse the tagged middle gain to obtain 20-symbol, budget-ten
predecessors, then reverse the gain-one first move to obtain 21-symbol roots
with budget eleven. Enumerate every gap distribution and support, verify the
actual forward gain and odd-cycle increase, and preserve the union of path tags
when class deduplication merges constructions.

Exit criterion: complete reports for all three inverse waves, independent
replay of every discharge, and no unresolved root.

## 5. Verify the complete proof

The CPU checker independently regenerates inventories, root classes, endpoint
classes, and all inverse waves directly from the certificate database. Missing
markers cannot suppress work; checksums and sidecars are not proof inputs.

Exit criterion: one full successful report covering all 83 root rows, all 104
endpoint annotations, all three inverse waves, every witness and closure, and
the stated diameter premises. Only that report permits the conclusion
TD(20) <= 11. Combining that checked upper bound with the independently
established reverse-permutation lower bound TD(20) >= 11 then proves the
project's objective TD(20) = 11.

Generated databases, count artifacts, checkpoints, logs, and certificates
remain untracked.
