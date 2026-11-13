# Mathematical knowledge and references

Joint-2 includes totally unoriented roots with even-length cycles. All-odd
totally unoriented roots and all totally unoriented successors stop for the
reasons proved in the source paper. The active direct count is R + O; R counts
unoriented residuals. All 83 rows are checked, with R = 124,395,153. See
[WORKFLOW.md](WORKFLOW.md) for Java counting and the versioned artifact formats.

[The TD(20) paper](td20-reduction-count.tex) specifies joint selection and the
complete root inventory. The [shared paper](../../td/docs/paper/main.tex),
Section sec:twisted-bracelets, supplies the bracelet framework and established
conventions. [The research plan](RESEARCH_PLAN.md) lists pending proof artifacts.

## Pairs, moves, and fixed points

Let N = n + 1. A cyclic-target pair is (omega, beta), where beta is the current
N-cycle, omega is even, and rho = omega * beta is the target N-cycle. Sorting
means reaching omega = identity and beta = rho. Validate a
CyclicTargetCandidate using asCyclicTargetPair() or requireCyclicTargetPair().

An applicable transposition is a triple (a,b,c) in cyclic beta order. It acts
by omega' = omega * tau inverse and beta' = tau * beta, preserving the target.
A 2-move increases the number of odd-length algebraic cycles by two; it is
qualifying if it also creates a fixed point. A 2-cycle is an algebraic object,
not a sorting move. The 3-norm is (N - number of odd-length cycles)/2, including
fixed points in that count. A qualifying move decreases it by one.

For a cycle gamma with support S, let beta_S be beta restricted to S. The
cycle is unoriented exactly when gamma = beta_S inverse. All other cyclic
orders are oriented; every 2-cycle is unoriented. An annotated type records
lengths and orientations with equal annotations indistinguishable.

Fixed points are explicit data in the general model, including singletons
omitted from a multicycle writing. Contraction preserves distance and 3-norm
(shared Propositions prop:adjacency-fixed-point and
prop:contraction-preserves-distance). The fixed-point-free encoding and
generator are used only after contraction. They do not assert a generator
for arbitrary singleton content.

## From fixed content to one representative per class

For a fixed-point-free annotated type, a color identifies an algebraic cycle.
Unoriented colors contribute repeated unranked beads, and oriented colors
contribute each cyclic rank once. Positions follow beta. Ranked orders that
decode to the unoriented order are excluded. This is the fixed-content encoding
of Proposition prop:fixed-content-encoding in the shared paper.

Auxiliary symmetries rename equal annotated colors and shift oriented rank
origins. StandardizedEncodingWord names colors by first occurrence and sets
the first oriented rank to zero. Internally, zero denotes an unranked bead;
mathematical cyclic ranks are stored with an offset of one.

Twisted reflection reverses word positions and inverts oriented ranks.
Rotation and this reflection define the twisted dihedral action; ordinary
word reversal is insufficient. A twisted bracelet is the orbit itself.
Decoder equivariance and realizability invariance identify realizable
bracelets with extended-toric classes (shared Theorem
thm:bracelet-class-correspondence). TwistedBraceletKey identifies candidate
orbits; ExtendedToricClassKey identifies valid-pair classes.

DirectTwistedBraceletGenerator compares standardized prefixes under these
symmetries and checks full-target realizability. The shared Theorem
thm:twisted-generation-correctness gives one representative per realizable
class. Burnside counting independently averages symmetry-fixed realizable
objects; [the counting guide](td20-burnside-counting.md) gives its exact
identity, rotation, and reflection terms. A class count is not a sorting result.

## Joint selection

Totally unoriented all-odd roots remain direct eleven-move obligations. Roots
with even-length cycles enter joint selection. Every eligible root
first tests moves creating at least two fixed points, which close by
1 + TD(18) = 11. Otherwise inspect every qualifying first move creating one
fixed point. After contraction, an oriented successor can use a second move
creating three fixed points, closing by 2 + TD(16) = 11, or two fixed points,
reaching 18 symbols with budget nine and 3-norm at most eight. Totally
unoriented successors have no structural outgoing edge.

On a fixed-point-free omega, any move creating two fixed points uses two
consecutive omega edges, so its triple has the form
(x, omega(x), omega squared(x)). Testing at most N such triples is complete,
including even-length cycles. All qualifying gain-one first supports are
checked, without a preferred cycle length. The paper proves this criterion.

The 214 endpoint types cover all fixed-point-free even annotated permutations
on 18 symbols with 3-norm at most eight. The 104 with an oriented 3-cycle close
by 1 + TD(14) = 9. Other endpoint classes need nine-move discharges or final
inverse reconstruction. Imported TD(18) witnesses must be replayed, and missing
nine-move results remain obligations even if obstruction markers are absent.

For oriented roots without a favorable prefix, the constrained generator
assigns current-cycle positions to canonical algebraic labels. Immediate
forbidden triples and forbidden pairs of moves reject prefixes as soon as
at most six required positions are known. Auxiliary cycle standardization,
rotation/twisted-reflection comparisons, and full-target checks leave exactly
one valid representative per residual class.

Support-clearing patterns relative to beta_S are powers 7 at length 9,
6 at length 11, 7/10/11 at length 13, and 9/13/15 at length 17. The shared
prop:td16-odd-cycle-reduction certifies their local sequences, and each obeys
(k+1)/2 + TD(20-k) = 11. These explicit patterns remove 29,000,964 roots.
The length-nine power 5 is not included; no length-fifteen clearing is assumed.

The active R + O total is 2,146,972,300. The paper records the complete singleton-21 count. Its structural residual
count does not indicate sorting failure. None is a proved sorting failure.
Endpoint residuals contribute additional final-phase reconstructed roots.

## Inverse coverage and verification

For each endpoint without a nine-move result, restore two fixed points in
all cyclic gaps and reverse every support containing both and one original
symbol. Retain valid oriented fixed-point-free predecessors only after exact
forward gain and odd-cycle checks. Deduplicate and screen within budget ten.
Then restore one fixed point in every gap and reverse every support containing
it and two original symbols. Every remaining valid root of either orientation requires
budget-eleven closure or a complete witness. Labeling restored symbols in current
traversal order loses no classes because relabeling transports all supports.

A stored prefix plus a known diameter certifies an upper bound. The verifier
replays the prefix and checks contraction and budget. A complete witness
must sort its pair within its budget.
Exhaustive reconstruction, disk deduplication, and regeneration of missing
endpoint obligations prevent a partial database from hiding work.

| Concept | Java location, relative to each module's package root |
| --- | --- |
| Pair validation and class keys | model/ |
| Fixed-content generation and Burnside counting | bracelet/ |
| Diameter premises and joint inventories | joint/ |
| Joint root constraints and support clearing | emitter joint/; checker independent verification code |
| GPU diameter exits and CUDA dispatch | search/; emitter base/ |
| Certificate encoding, storage, and replay | certificate/ |
| Complete inverse waves | avoidance/ |

The package root is br.unb.cic.td20. See [the architecture](ARCHITECTURE.md)
for module and persistence boundaries.
