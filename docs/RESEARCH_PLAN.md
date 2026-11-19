# Research plan and proof completion

The objective is a checked upper bound of eleven transpositions for every
ordinary order-20 instance. Together with the established reverse-permutation
lower bound TD(20) >= 11, this would establish TD(20) = 11. The upper bound is
**not yet proved**. [The source paper](td20-reduction-count.tex) specifies the
two-sided Eriksson-plus-third-move route. TD(16) = 9 and TD(18) = 10 are
established results using the same computational methodology and explicit
completed premises, alongside the smaller established bounds.

The user authorized replacement of joint-2 on 16 November 2026. Its completed
U/J/C/O/R counts and 214-annotation endpoint census describe a superseded route;
they cannot supply counts or sorting coverage for the active route.

On 17 November 2026 the user authorized reuse of the completed TD18
budget-nine results and reconstruction directly from the remaining 18-symbol
second-level residuals. The two-sided mathematical route and all inventories
remain unchanged. Runtime reconstruction no longer starts from the stored
16-symbol obstruction markers and performs no budget-nine or budget-ten
sorting searches. Independent proof checking still derives every obligation
from complete endpoint generation and actual witness replay.

On 19 November 2026 the user authorized longer validation, production
integration and reconstruction restart once the support-family method is
sound. The source paper now proves elementary final-root family closures.
The complete endpoint, after-two and after-one source sets remain unchanged.
The final persisted inventory contains only reconstructed roots with no
immediate gain-at-least-two move on either endpoint; elementary roots are
checked directly by the independent regeneration algorithm. This changes the
final inventory meaning and requires a distinct checkpoint/report version.
It neither resumes budget-ten sorting nor treats benchmark results as proof
coverage. Deployment and completed execution evidence are recorded separately.

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
Validate the final support-family lemma with the actual successor convention:
an inverse on one endpoint leaves the other cyclic order unchanged, and a
consecutive triple `(x,v(x),v²(x))` on that endpoint survives whenever the
inverse support avoids `{v(x),v²(x)}`. Restored fixed symbols preserve old
cyclic order on both endpoints. Check the equivalent bounded vertex-cover
condition, including sources with no masks, both inverse endpoints and every
gap. Do not strengthen the condition to require avoiding `x` or turn a failed
sufficient family test into evidence that a parent is hard.

Exit criterion: a checked coverage derivation agreeing with the paper and both
implementations, and passing complete small-domain class-set and arbitrary-support
forward/inverse oracles, including gains zero through three. The filtered
final generator must match complete inverse enumeration followed by an
independent arbitrary-support immediate-closure oracle, in exact surviving
class sets and per-class multiplicities before deduplication. Include actual
forward-gain replay, endpoint interchange, arbitrary relabeling, fixed-point
preconditions, and zero-survivor cases. Each module implements the family
checks independently; the checker does not depend on the emitter artifact.

Completed unit validation includes both modules' exhaustive retained-class
comparisons for every domain of three through eight symbols against an
arbitrary-support oracle on the actual current and target cycles, with duplicate
classes rejected. Separate passed tests compare serial and parallel counts and
check the 21-symbol reverse exception. These scopes validate the port; they do
not supply exhaustive 21-symbol count or witness-replay evidence.

## 2. Independently regenerate counts and generation

Regenerate all 83 fixed-point-free even root partitions of 21 and all 143
orientation annotations from the 28 even endpoint partitions of 16. Algebraic
2-cycles are unoriented. Every endpoint annotation is included without a stricter
3-norm cutoff, including types with zero realizable classes. The endpoint census
is complete and agrees between both modules in every row:
14,098,353,328 classes, with 11,523,939,269 outside elementary closures.
The paper contains all 83 complete emitter root-count rows: U3 = 0, O3 = 8
and C3 = 2, leaving six direct obligations. The standalone checker census
completed the first 24 matching reports before an intentional stop; 59 rows
remain. This criterion still requires independent agreement in every row.

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

Independently regenerate each residual 16-symbol endpoint from missing
eight-move witnesses. Reverse its third gain-two move by restoring two fixed
symbols in all gaps and testing every compatible support on both endpoints.
The resulting complete 18-symbol after-two inventory has budget nine.

The completed read-only TD18 reuse probe checked 5,032,950 after-two classes:
4,062,619 have replayable current-endpoint diameter-prefix certificates within
nine moves, and 970,331 have no matching result under either endpoint key.
The matching versioned records have now been copied into project-root
`certificates/`, and every residual has been persisted with its 18-symbol
domain, budget nine and exact count under
`fleet/production-td20-20261117/td18-after-two-reuse-v1/residuals`.
The completed `td18-after-two-import-v1.txt` records source and destination
replay. The checker-only `independent-import-scope-v1.txt` additionally
replayed every copied result and checked that the persisted residual set
equals exactly the missing classes, including all residual markers.
The independent checker must replay the TD20 results and derive the complete
residual set itself; neither a stored obstruction list nor a probe/import
report is a proof input. The probe is completed lookup evidence, not a
completed TD20 proof-verification report. The independent import audit also
does not regenerate the endpoint inventory or replace full proof checking.

Active emitter reconstruction starts from these second-level residuals.
The 2,036 stored 16-symbol obstruction markers are informational and are not
runtime reconstruction inputs. Every unclosed 18-symbol state reverses a
gain-two second move to obtain 20 symbols with budget ten, or a gain-three
second move to obtain 21 symbols with budget ten. Cover every such state
without budget-ten sorting or certificate lookup. Reverse respectively the
gain-one first move by restoring one fixed symbol, or the gain-zero first
move without restoring a symbol. Both inverse operations cover both endpoints.
No odd-cycle qualifying restriction may remove a gain-zero/one first move.

Let `C` be the complete budget-ten source set, `P` the union of all its valid
final inverse classes, and `D11` the fixed-point-free 21-symbol classes with an
immediate gain-at-least-two move on either endpoint. The final inventory is
exactly the set difference `R = P minus D11`. For every source and inverse endpoint, independently
derive all witness masks from the actual pair. Supports which miss some mask
have the proved budget-eleven closure over every gap and need not be
materialized. A source-endpoint family closes in full only if the mask graph
has no vertex cover with at most the required two or three old symbols.
Otherwise retain every old support meeting every mask and every restored gap.
Every explicitly accepted parent passes actual forward-gain and pair replay.
Test both endpoints for an immediate gain of at least two before constructing
its canonical key; verify the closing transposition and apply `1 + TD(18) = 11`.
Deduplicate the remaining valid-pair classes `R` on disk with bounded
persistence and require their budget-eleven certificates.

These families are directly checked elementary closures, not guessed witness
lists. Their universal proof is in the source paper, and the checker derives
and verifies its hypotheses from every independently regenerated source.
No per-source family certificate, source marker, stored mask list or emitter
report is a proof input. This is the same boundary used for the elementary
closures in direct root generation. Every intrinsic result required for `R`
still comes from the certificate database and is replayed. Missing source
work, missing residual results, interrupted queues, advisory prefetch misses
and evicted cache entries cannot omit a proof obligation.

Exit criterion: a complete versioned import report and persisted TD20 results
for every imported class, plus an independently regenerated complete
three-wave report with full after-two, closed and second-level residual counts,
both inverse branches and endpoints, the exact final residual inventory `R`,
and no unresolved final root. The version-three checker report
`inverse-coverage-v3.txt` identifies `rootInventory=ELEMENTARY_RESIDUALS` and
`elementaryClosure=two-sided-immediate-gain-at-least-two-v1`, gives the exact
non-elementary final-root count, and records
that no budget-ten certificate lookup pruned the final roots. If a wave or `R`
is empty, the full checker must still check and report its complete source scope.
Historical full-search, unfiltered residual-expansion and family-filtered
checkpoints are distinct modes or versions. Filtered completed-root checkpoints
use `td-eriksson-reconstruction-v4`, retain their original `FULL_SEARCH` or
`RESIDUAL_EXPANSION` mode, and record
`rootInventory=ELEMENTARY_RESIDUALS`; their `roots` count means `|R|`. Both
execution modes use the filtered final wave without budget-ten search or
certificate pruning. Production flushes certificates before publishing a
downstream checkpoint; a failed flush preserves the preceding boundary.
A completed old after-two or
after-one residual-expansion source can be explicitly adopted after validating
its unchanged domain, budget, route, mode and exact inventory. Old complete-root
checkpoints cannot be relabeled as filtered-root checkpoints, and TD18 or
full-search checkpoints cannot substitute for this input. Interrupted final
output is regenerated from its completed source boundary.
After residual intermediate generation completes and all writers drain,
`countKeys()` counts distinct persisted budget-ten classes through RocksDB's
merged logical keys using parallel, disjoint half-open ranges on one snapshot.
All range workers join and every iterator status is checked before summing and
publishing the exact inventory. Approximate byte sizes guide range scheduling
only. It does not decode or validate states, invoke
prefetch, or perform an additional Java deduplication. Publish that exact count
in the `AFTER_ONE` checkpoint before final inverse generation starts. Legacy
version-three checkpoints with `afterOne=-1` remain readable only for completed
residual generation; resume performs the key-only count and atomically upgrades
the checkpoint before proceeding. Final expansion logs completed expanded
source classes as `processed=X/N (P%)`, against the exact fixed source total
`N`, and estimates its ETA from the observed rate. The completion percentage
comes from completed source coverage, including proved whole families,
independently of output counters.
Accumulated generated, submitted, consumed and SST-ingested output key
occurrences are operational counters that may include duplicates. They are
separate from the exact final residual-root inventory established by the
mandatory root scan, and do not alter independent checker obligations. Candidate
support/gap counts for analytically closed families need not be counts of valid
inverse parents, and no occurrence counter determines the number of distinct
elementary classes. No count of the unmaterialized sets `P` or `P ∩ D11` is
required or claimed by the filtered protocol.

## 5. Independently verify the entire proof

Run `tools/td20 verify --phase=all` against the finished certificate database
with fresh checker work and no range omissions. It must independently regenerate
inventories, roots, endpoint classes and inverse waves; enforce every count and
budget; independently verify the family and individual elementary closures;
replay all required witnesses for the remaining classes; and reject missing results. Emitter
checkpoints, checksums and sidecar manifests are not proof inputs.

Record emission and verification separately, with their actual source revisions,
runtime settings, exact regenerated counts, phase timings, success messages
and exit status. Audit source changes between the two runs. Keep test executions
and versioned census reports as distinct evidence: a replay success cannot
mark an unexecuted test or unpublished report checked. Fresh TD20 emission and
full independent checking will supply new execution logs; the result remains
unproved until every exit criterion has its required artifact.

Exit criterion: both required Maven CPU suites and one complete successful
end-to-end checker report, together with every counted inventory, replayed
database and cited mathematical premise. CUDA-dependent tests require a configured
environment; a proof using only CPU certificates need not use CUDA.

All five criteria require checked artifacts. Structural regeneration and tests
alone do not establish TD(20) = 11. Emitter root counts are published and the
endpoint census is independently checked. Independent completion of the root
census, sorting and the complete proof-checker report remain pending until
their actual artifacts exist.
Generated databases, checkpoints, reports, certificates and logs remain untracked.
Record completed execution metadata and artifact locations in
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md); see
[VALIDATION.md](VALIDATION.md) for the procedure.
