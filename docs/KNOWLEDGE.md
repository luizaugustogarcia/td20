# Mathematical conventions and active obligations

Let `(omega, beta)` be a cyclic-target pair, with target `rho = omega * beta`.
Here beta is the current cycle and omega is the algebraic permutation. Require
a full target cycle before treating a candidate as a pair. Use
`asCyclicTargetPair()` or `requireCyclicTargetPair()` to validate candidates.
The sorting move is a transposition represented by an applicable 3-cycle;
reserve 2-cycle for an algebraic cycle. State odd-length/even-length cycle
parity explicitly. Fixed points are retained in the general model and contracted
before fixed-point-free encodings, generators and inverse operations.

A cycle on support S is unoriented when it is the inverse of beta restricted to
S. A qualifying 2-move increases the odd-length-cycle count by two and creates a
fixed point. The active Eriksson route allows gain-zero and nonqualifying first
moves; it also tests totally unoriented all-odd roots.

The first two moves must produce at least three fixed symbols; the additional
move must produce at least two more. Moves can change either current or target.
Swapping endpoints gives `(omega inverse, rho)` and preserves distance, but is
not added to extended-toric class equivalence. This is a triangle-inequality
upper-bound route, rather than a fixed-target forward prefix assertion.
TD(16) = 9 and TD(18) = 10 are established using the same computational
methodology and are explicit completed premises. Smaller established bounds
are also premises.

Root obligations have 21 symbols and budget 11. Three moves producing
five fixed symbols leave 16 symbols and budget 8. The endpoint cover contains all 143 even fixed-point-free annotated types on 16 symbols, with budget 8.
No stronger endpoint 3-norm bound is imposed without proof. Every root with no
favorable route is independently regenerated as a direct obligation; support
clearing uses only the paper's explicit local patterns.

Fixed-content words color algebraic cycles. Oriented colors carry cyclic ranks;
unoriented colors have unranked repeated beads. Equal-color renaming and rank
origin shifts are auxiliary symmetries. Auxiliary standardization fixes colors
by first occurrence and the first rank at zero. The twisted dihedral action
rotates positions or reflects them while inverting oriented ranks. A twisted
bracelet is an orbit, not a canonical word; reflection is not word reversal.
Realizable orbits correspond to valid-pair classes through decoder equivariance
and full target realizability. Use `TwistedBraceletKey` for candidate orbits and
`ExtendedToricClassKey` for validated pair classes.

Inverse coverage reverses gains two, then either two followed by one or three
followed by zero, on either endpoint. Restore fixed symbols in every cyclic gap
and enumerate all supports. Actual forward replay, not an assumed obstruction
list or odd-cycle shortcut, determines admission. Remaining roots require full
budget 11. A sorting witness closes only its represented class and budget.
See [the source paper](td20-reduction-count.tex) for the coverage argument,
local bounds, complete inventories, and the completed computer-assisted proof
of TD(20) = 11. The independent verification is recorded in
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md).
