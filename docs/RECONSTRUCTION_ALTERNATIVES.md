# Reconstruction family closures and further alternatives

The family closure below is proved in [the source paper](td20-reduction-count.tex), which remains
authoritative, and is required by [the research plan](RESEARCH_PLAN.md). The
final inventory consists of the non-elementary reconstructed roots; the
checker derives the elementary family closures independently. Every explicit
inverse parent retains actual forward replay, and every remaining class
requires a persisted budget-eleven result. Runtime deployment and completed
coverage are separate execution facts. No result here proves TD(20) = 11.

## Mathematical opportunity: close inverse families before making class keys

The final inverse wave restores either one fixed symbol to a 20-symbol source
or no symbols to a 21-symbol source. Before replay rejection and class
deduplication, these branches inspect respectively

```text
2 * 20 * binomial(20, 2) = 7,600 candidates per 20-symbol source,
2 * binomial(21, 3)      = 2,660 candidates per 21-symbol source.
```

The factor two accounts for both endpoints. An immediate gain-at-least-two
move of a valid fixed-point-free final parent closes that parent using
`1 + TD(18) <= 11`. Checking this condition before canonicalization can avoid
constructing keys, writing records and sorting these easy parents. It does
not justify omitting an unresolved parent. The former complete-root protocol
materialized even these easy parents. The adopted protocol instead verifies
their elementary closures and persists the remaining classes.

There is also a stronger test on an entire inverse family. The following
derivation uses the repository's right-to-left function composition.

Let `(Omega, beta)` be a valid contracted source, let `rho = Omega * beta`,
and first consider an inverse on the current endpoint. Let `v = Omega^-1`.
For every old symbol `x`, set `y = v(x)` and `z = v(y)`. Retain the triple
when its symbols are distinct and occur in order `(x,y,z)` around `rho`.
The consecutive-triple lemma in the source paper makes it an applicable
gain-at-least-two move on the opposite endpoint `(v,rho)`. Associate the
two-symbol mask `{y,z}` with this triple. Do not require the inverse support
to avoid `x`.

Restore the required fixed symbol, if any, in any cyclic gap, extending
`Omega` by the identity there. Relative cyclic order on the old symbols of
both `beta` and `rho` is unchanged. Let `tau` be the inverse transposition,
with support `S`, and let the resulting parent be

```text
P = (Omega * tau^-1, tau * beta).
```

The opposite endpoint of this parent is

```text
P* = (tau * v, rho).
```

If `S` avoids `{y,z}`, then `(tau*v)(x)=y` and `(tau*v)(y)=z`. Thus the
same consecutive triple remains applicable around the unchanged `rho` and
still creates at least two fixed symbols. This proves the sufficient
condition

```text
some witness mask {y,z} is disjoint from S
    => every valid parent with that support, in every gap, has the closure.
```

All restored symbols are outside every mask, so only the old part of `S`
needs to be tested. For an inverse on the target endpoint, exchange
endpoints first: use `v=Omega` and the order `beta` to build the masks. This
is an explicit endpoint interchange, not an additional class equivalence.

The masks are edges of a graph on the old symbols. A support survives this
sufficient closure exactly when its old symbols form a vertex cover of that
graph. For inverse gain `g` there are `3-g` old support symbols. Consequently
no vertex cover of size at most `3-g` means the entire endpoint family closes.
This includes every gap, and is much stronger than checking each parent
separately.

The cover test needs no general graph solver. Select an uncovered edge
`{u,v}` and branch on selecting `u` or `v`; depth is at most three, giving at
most eight leaves. Alternatively, every edge is between consecutive
symbols of an algebraic cycle, so the graph is a disjoint union of paths
and cycles. Its minimum vertex-cover size is the sum of `floor(L/2)` for
paths with `L` vertices and `ceil(L/2)` for cycles with `L` vertices. A
support enumeration implementation could generate only covers of size
`3-g`, completing smaller covers with arbitrary remaining symbols and
removing duplicate support masks. When there are no witness edges, this
test removes nothing.

The original isolated Java workspace
`emitter/src/main/java/br/unb/cic/td20/experimental/InverseFamilyClosure.java`
implements the masks and bounded cover decision. It supplied the initial
experiment before production integration. Its small-domain test enumerates all
valid fixed-point-free normalized source pairs of three through six
symbols, both endpoints, inverse gains zero and one, every support and
every gap. For every predicted closure it constructs and replays the actual
parent, then checks the opposite endpoint using an independent exhaustive
arbitrary-support move oracle. A family prediction is a mathematical
sufficient condition; the test is implementation evidence on that stated
scope, not a TD20 coverage artifact.
The focused `InverseFamilyClosureTest` run passed both tests and examined
6,282 valid inverse-parent occurrences on this complete small-domain scope.

## Adopted independent coverage protocol

Let `C` be the complete budget-ten source set, `P` the union of its valid
two-sided final inverse classes, and `D11` the classes having an immediate
gain of at least two on either endpoint. Define the final residual set to be
`R = P minus D11`. The checker regenerates every source in `C` from complete
endpoint generation and replayed budget-nine results. For each source and
inverse endpoint it independently derives the opposite algebraic images,
cyclic positions and witness masks and checks every mask's hypotheses.
It may then verify a whole support family using the quantified lemma,
including all its gaps. A bounded vertex-cover decision may certify every
support of that source endpoint at once. Sources with no masks receive no
family exemption. The other supports retain every required gap, actual
forward gain and full pair replay.

Each accepted explicit parent is tested on both endpoints for an applicable
consecutive triple creating at least two fixed symbols, which is equivalent
to an arbitrary-support immediate-gain test. Those with a closing move are
checked against `1 + TD(18) = 11`; the rest are canonicalized and deduplicated
to give exactly `R`. The checker demands a stored intrinsic certificate for
every class in its own independently generated `R` and replays it within
eleven moves. It never reads emitter masks or filtering decisions.

This protocol needs no separate per-source family certificate in the witness
database. The family lemma is an elementary mathematical closure, and its
hypotheses are computed and verified directly from the regenerated valid
pair, just as initial root generation uses elementary diameter closures.
Thus the earlier investigative suggestion to store one closure record per
source is unnecessary. No missing marker can omit work: the checker derives
every source and its complete applicable family scope itself. Every
non-elementary witness still comes from the database, and a missing one fails
verification. This preserves the distinction between analytic closures,
generated inventories and replayable intrinsic witnesses.

The source paper, research plan, checker and versioned checkpoints describe
this same boundary. Historical final checkpoints count the entire distinct
root set `P`; filtered final checkpoints count `R` and must use a distinct
version and explicit inventory kind, while retaining the execution mode.
Both FULL_SEARCH and RESIDUAL_EXPANSION use the filtered final wave.
Completed historical after-two/after-one residual-expansion
sources may be explicitly adopted only after their unchanged domains, budgets,
mode and exact inventory have been validated. Complete-root, full-search and
TD18 checkpoints cannot be silently reinterpreted. Report examined source
classes, candidate occurrences, mathematically discharged families or
occurrences, and exact persisted residual classes separately. Neither a family
occurrence count nor a candidate occurrence count is a count of distinct
closed root classes. A closed support family can include candidate gap slots
which would fail the original fixed-point-free inverse acceptance test, so
its size must not be reported as an accepted-parent count.
Missing source work, a failed writer or an unresolved root must prevent
completion. Production checkpoint compatibility cannot be inferred from
matching directory names.

This protocol operates at final budget eleven. It does not reinstate
budget-ten search or certificate pruning of the intermediate inventory,
and it does not suppress gain-zero first moves. Every current proof
obligation remains either checked directly or represented by an explicit
residual.

## Other algorithmic choices and their limits

* **Batch CUDA work.** Fuse inverse construction, actual gain checks,
  immediate closure detection, canonicalization and compacted output.
  Returning only surviving keys would reduce transfer and disk traffic
  under the proposed residual protocol. Without it, speedups in the kernel
  leave the current full-output storage requirement unchanged. Keep exact
  overflow handling: a full output buffer must trigger another batch or
  retry, never truncation. Preserve existing five-bit canonical keys.
* **Apply existing elementary root closures before canonicalization.**
  Immediate gain at least two on either endpoint is linear in the domain
  using consecutive algebraic triples. Two moves giving four fixed
  symbols or three giving six provide further established closures. A
  richer prefix costs more per survivor and needs measured break-even
  rates. Use only the paper's explicit support-clearing patterns.
* **Generate from the root side.** Directly enumerate roots having no
  immediate closure, and test their forward routes against the checked
  residual inventory. This trades inverse multiplicity for a potentially
  large direct domain. The current retained-root generator excludes roots
  with successful joint routes, so it is not already this enumerator.
  Count and benchmark the intermediate restricted root domain before
  committing to this alternative. Keep all 83 active partitions.
* **Canonical augmentation needs membership information.** Selecting one
  arbitrary canonical predecessor per root is unsound: the selected
  predecessor may lie outside the after-one inventory although another
  predecessor lies inside. Selecting the minimum predecessor *within*
  that inventory is sound, but requires complete eligible predecessor
  enumeration, class keys and membership queries. Source stabilizers
  remove only symmetry duplicates and can be trivial on generic inputs.
* **Reduce duplicates before storage.** Exact batch deduplication and
  partitioned merges can remove repeated keys before RocksDB ingestion.
  Hash-only deduplication, lossy Bloom-filter acceptance and advisory
  caches cannot remove obligations. These techniques help only according
  to the measured multiplicity and write share of the runtime.

No unmeasured factor should be multiplied into a promised 100-fold
end-to-end speedup. Measure source classes per second, accepted inverse
occurrences, closure survival fraction, key throughput, output bytes and
exact distinct residual counts. The first full-domain residual count is
unknown until checked. The same bottleneck must also be removed from the
independent checker, or emission can finish quickly while verification
still takes months.

## Insights and limits from Genetics 14 Oct 2026

The consulted manuscript is
`/home/luiz/Temp/docs/Genetics 14 Oct 2026.pdf`, titled *Minimal excluded
permutations in a model of genome evolution*. Its notation uses right actions, so
algebraic formulas must be translated before copying into this repository.

Lemma 2.1 and Corollary 2.2 prove distance-preserving contraction of runs
and explicitly lift a contracted sorting path. This supports the fixed
symbol contraction boundary. It does not permit contracting arbitrary
symbols. Lemmas 2.8--2.10 give the cycle graph model and exactly one
applicable orientation of each three-symbol support. Together with the
fact that a move changes three images, these support the active inverse
construction: restore every created fixed symbol in its actual gap,
include every compatible support, choose the applicable inverse
orientation, and replay forward. They do not supply a reason to discard
one endpoint or either `(1,2,2)` or `(0,3,2)` branch.

Lemma 2.4 proves that deleting any one entry lowers distance by at most
one. Therefore a root can be closed by selecting `k` deleted entries and
finding a certified bound at most `11-k` for the standardized remainder;
reinsertion lifts the result. This offers an alternate certificate search
using deletion choices rather than move sequences. Deletion is not free
contraction, and the present certificate format would need to represent
and independently replay the insertion/lifting argument.

The known diameter TD(18)=10 plus two arbitrary insertions gives twelve,
not eleven. Thus deletion alone does not solve TD20. Useful deletion
certificates require a stronger bound on the specific remainder, additional
valid contractions, or a proved one-move insertion of a larger block.
For example, testing two-entry deletions is only quadratic in the root
size, but a useful remainder must have a replayable bound nine. Whether
the completed smaller databases cover enough such remainders is a
measurement, not a consequence of their diameter.

Lemma 2.6 gives another possible root closure: if a normalized permutation
is a direct sum of smaller blocks, sorting those blocks separately gives
the sum of their certified bounds. This is sound when the decomposition
and the sum at most eleven are checked. Its prevalence among the remaining
roots must be measured; it is not a universal bound.

Lemmas 2.11--2.14 give rank lower bounds and restrictions on rank-decreasing
moves. They can guide searches and certify certain lower bounds, but
lower bounds cannot discharge a required upper-bound witness. In
particular, restrictions for all-odd algebraic cycles are not permission
to discard the gain-zero first moves required by the active route.
The manuscript's constructions of minimal excluded permutations do not
classify all minimal exclusions, so their lengths alone do not establish
the absence of a radius-eleven obstruction at order twenty.
