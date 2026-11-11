# Mathematical knowledge and references

[The TD(20) source specification](td20-reduction-count.tex) defines the local
joint-3 argument. The sibling `../td` repository supplies only the shared
twisted-bracelet framework and established conventions. The premise
`TD(18) = 10` is assumed as directed; it is not reproved here.

## Equality objective and established lower bound

The project objective is `TD(20) = 11`. The reverse permutation in `S_n` has
transposition distance `floor(n / 2) + 1` for `n >= 3`, hence its distance in
`S_20` is 11 and `TD(20) >= 11`. The
[shared paper](../../td/docs/paper/main.tex) records this formula and its
original reference. This established lower bound is independent of the
joint-3 route and all class counts. The remaining proof direction is the
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

## Exact joint-3 selection

Roots are fixed-point-free valid pairs on 21 symbols with budget 11. Totally
unoriented states receive no structural reduction at any depth. A
known-diameter closure may still be used when its checked inequality fits.
For every other root the complete routing rule is:

1. An immediate move of gain at least two closes, because contraction leaves
   at most 19 symbols and `1 + TD(18) = 11`.
2. Otherwise inspect every qualifying gain-one first move.
3. At an oriented successor, a gain-three second move closes. Gain-one and
   gain-two second moves continue; no other second gain is retained.
4. After gains `(1,1)`, a gain-three third move reaches a 16-symbol endpoint.
5. After gains `(1,2)`, a gain-two third move reaches a 16-symbol endpoint,
   while a gain-three third move closes on at most 15 symbols.

Thus the favorable depth-three sequences are `(1,1,3)`, `(1,2,2)`, and
`(1,2,3)`. The first two carry distinct route tags to the endpoint even when
they produce the same endpoint class. The two-move closure `(1,3)` is also
part of the favorable-prefix test. Every first, second, and third support is
tested by actual applicability and odd-cycle gain, including supports inside
even-length cycles and across cycles.

Any move creating at least two fixed points has, up to cyclic writing, support
`(x, omega(x), omega squared(x))`. This gives a complete linear-size test for
such moves. Gain-one moves are still enumerated exhaustively. Prefix pruning
is sound only when it encodes the whole rule above; leaf replay is the final
check.

Each endpoint has 16 nonfixed symbols, budget 8, and 3-norm at most 7. The
complete cover contains 104 annotated types. Exactly 52 contain an oriented
3-cycle and close by one local move followed by `TD(12)`, costing at most 8.
Every other endpoint requires a replayed result within eight moves or enters
inverse reconstruction. No predicted endpoint-failure count is a premise.

## Direct roots and support clearing

An oriented joint-3 residual has none of the favorable routes above. It is a
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
table records totally unoriented classes `U`, oriented joint-3 residuals `J`,
the support-cleared subset `C`, and `O = J - C`. Direct obligations total
`U + O`. Those totals remain pending until complete artifacts agree.

## Three inverse waves

For every endpoint class without an eight-move discharge, preserve each route
tag and reverse exactly the corresponding gains:

1. Reverse the third move. Tag `(1,1,3)` restores three fixed points and gives
   a 19-symbol predecessor; tag `(1,2,2)` restores two and gives an 18-symbol
   predecessor. Both have budget 9.
2. Reverse the tagged second move. Restore one fixed point for `(1,1,3)` or
   two for `(1,2,2)`, giving a 20-symbol predecessor with budget 10.
3. Reverse the gain-one first move, giving a 21-symbol root with budget 11.

At every wave enumerate all cyclic gaps and compatible supports, then replay
the forward move and verify its exact fixed-point gain and odd-cycle increase.
Deduplication must retain the union of route tags. A checked result at a wave's
budget closes every represented path; unresolved classes propagate. Every
remaining reconstructed root needs a budget-11 result.

The checker independently regenerates the 83 root rows, all 104 endpoint
annotations, and all inverse waves. Missing obstruction markers, checksums,
and sidecar manifests cannot remove obligations. A count is not a sorting
witness, and a witness proves an upper bound only for its represented class.

The Java package root is `br.unb.cic.td20`. See
[the architecture](ARCHITECTURE.md) for module boundaries.
