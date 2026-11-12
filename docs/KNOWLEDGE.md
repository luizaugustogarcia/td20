# Mathematical knowledge and references

[The TD(20) source specification](td20-reduction-count.tex) defines the local
joint-2 argument. The sibling `../td` repository supplies only the shared
twisted-bracelet framework and established conventions. The premise
`TD(18) = 10` is an established, independently checked premise; it is not reproved here.

## Equality objective and established lower bound

The project objective is `TD(20) = 11`. The reverse permutation in `S_n` has
transposition distance `floor(n / 2) + 1` for `n >= 3`, hence its distance in
`S_20` is 11 and `TD(20) >= 11`. The
[shared paper](../../td/docs/paper/main.tex) records this formula and its
original reference. This established lower bound is independent of the
joint-2 route and all class counts. The remaining proof direction is the
certificate-checked upper bound `TD(20) <= 11`.

## Pairs, moves, and fixed points

Let `N = n + 1`. A cyclic-target pair is `(omega, beta)`, where `beta` is the
current `N`-cycle, `omega` is even, and `rho = omega * beta` is the target
`N`-cycle. A `CyclicTargetCandidate` is unchecked until converted with
`asCyclicTargetPair()` or `requireCyclicTargetPair()`.

A sorting transposition is represented by a triple `(a,b,c)` in cyclic
`beta` order. It acts by `omega' = omega * tau inverse` and
`beta' = tau * beta`. The term 2-cycle is reserved for an algebraic cycle.
A 2-move increases the number of odd-length algebraic cycles by two. It is
qualifying when it also creates a fixed point; its gain is the number of new
fixed points.

The 3-norm is `(N - c_odd(omega))/2`, with fixed points included in
`c_odd`. Contract fixed points before using any fixed-point-free encoding or
generator. Contraction preserves distance and 3-norm.

For an algebraic cycle `gamma` with support `S`, let `beta_S` be the current
cycle restricted to `S`. The cycle is unoriented exactly when
`gamma = beta_S inverse`. Every algebraic 2-cycle is unoriented. A state is
totally unoriented when all its cycles are unoriented.

## Fixed content and symmetry

For a contracted fixed-point-free annotated type, a color identifies an
algebraic cycle. Unoriented colors contribute repeated unranked beads;
oriented colors contribute each cyclic rank once. Auxiliary standardization
renames equal annotated colors by first occurrence and shifts each oriented
rank origin to zero.

Rotation and twisted reflection act on positions. Twisted reflection reverses
positions and inverts oriented cyclic ranks; ordinary word reversal is not the
correct action. A twisted bracelet is the whole orbit, not a canonical word.
`TwistedBraceletKey` deduplicates candidate orbits and
`ExtendedToricClassKey` identifies classes of valid pairs. Full-target
realizability must be checked before a candidate becomes a pair.

## Exact joint-2 selection

Roots have 21 nonfixed symbols and budget eleven. Totally unoriented states
receive no structural reduction at either depth. Immediate gain at least two
closes by 1 + TD(18) = 11. Otherwise inspect every qualifying gain-one first
move and every second move creating at least two fixed points. Gain three
closes by 2 + TD(16) = 11; gain two reaches an 18-symbol budget-nine endpoint.
Include supports within even-length cycles and across cycles.

Every gain-at-least-two move uses (x, omega(x), omega squared(x)) up to cyclic
writing in beta order. This complete consecutive-edge test gives safe prefix
predicates. Gain-one first supports remain exhaustive.

Endpoints have 3-norm at most eight. The cover has 30 partitions and 214
annotations; 104 close by one oriented-3-cycle clearing plus TD(14) = 8.
Every class in the other 110 annotations needs a nine-move result or inverse
coverage. No endpoint-failure count is a premise.

## Direct roots and support clearing

An oriented joint-2 residual has none of the favorable routes above. It is a
direct root unless one of these explicit local powers clears its support:

| Support length | Powers relative to `beta_S` | Clearing length | Bound |
| ---: | --- | ---: | --- |
| 9 | 7 | 5 | `5 + TD(11) = 11` |
| 11 | 6 | 6 | `6 + TD(9) = 11` |
| 13 | 7, 10, 11 | 7 | `7 + TD(7) = 11` |
| 17 | 9, 13, 15 | 9 | `9 + TD(3) = 11` |

These are the only support-clearing patterns admitted by this route. The root
inventory consists of the 83 nonincreasing partitions of 21 into parts at
least two having an even number of even parts. For each partition the source
table records totally unoriented classes `U`, oriented joint-2 residuals `J`,
the support-cleared subset `C`, and `O = J - C`. Direct obligations total
`U + O`. Those totals remain pending until complete artifacts agree.

## Two inverse waves

Restore gain two in every gap distribution, testing supports with both restored
symbols and one survivor. This gives 20-symbol budget-ten predecessors.
Restore gain one in every gap, testing supports with it and two survivors.
This gives 21-symbol budget-eleven roots. Require valid fixed-point-free,
non-totally-unoriented predecessors, exact forward gain, and odd-cycle increase
two. Disk deduplication preserves every compatible class; all paths use (1,2).

Checked results close represented paths; unresolved classes propagate. The
checker regenerates all 83 root rows, 214 endpoint annotations, and both waves.
Missing markers, checksums, and sidecars cannot remove work. A count is not
a witness. The selection-independent U count is retained; J/C/O are pending.

The Java package root is `br.unb.cic.td20`. See
[the architecture](ARCHITECTURE.md) for module boundaries.
