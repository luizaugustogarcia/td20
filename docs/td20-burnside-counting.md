# Counting realizable twisted bracelets with Burnside's identity

For ordinary order \(n\), set \(N=n+1\) and fix the current cycle
\(\beta=(0\,1\,\ldots\,N-1)\). The objects being
counted are fixed-point-free permutations \(\omega\) of the prescribed
annotated cycle type for which \(\rho=\omega\beta\) is an \(N\)-cycle.
Unoriented cycles follow the restriction of \(\beta^{-1}\) to their
supports; every other cyclic order is oriented.

The decoder-equivariance and Burnside results of the upstream
twisted-bracelet framework in `../td` identify bracelets with the orbits
of these permutations under the twisted dihedral action. Auxiliary color
permutations and rank-origin shifts have already been quotiented out.
Consequently, the number of realizable bracelets is

\[
B=\frac{I+\sum_{\nu=1}^{N-1}R_\nu+\sum_{\nu=0}^{N-1}S_\nu}{2N}.
\]

Here \(I\) counts every realizable permutation of the annotated type;
\(R_\nu\) counts those commuting with \(\beta^\nu\); and \(S_\nu\)
counts those satisfying \(f_\nu\omega^{-1}f_\nu=\omega\), where
\(f_\nu(x)=\nu-x\pmod N\). Computing these terms by enumerating every
labelled permutation would require enumerating the entire labelled family. The
following constructions avoid that enumeration.

## Identity term and orientation refinement

Let \(m_j\) be the number of cycles of length \(j\) and \(u_j\) the
required number of unoriented cycles of that length. For a vector
\(r=(r_j)\), let \(F(r)\) count realizable permutations together with a
selection of \(r_j\) of their unoriented \(j\)-cycles. These selected
cycles are unordered within each length. Binomial inversion gives

\[
I=\sum_{u\le r\le m}(-1)^{\sum_j(r_j-u_j)}
       \prod_j\binom{r_j}{u_j}\,F(r).
\]

Indeed, a permutation having \(t_j\) unoriented cycles contributes
\(\prod_j\binom{t_j}{r_j}\) to \(F(r)\). Its coefficient in this sum
is one when every \(t_j=u_j\) and zero otherwise, by the ordinary
binomial identity. Thus the formula preserves the orientation annotations;
the unrestricted cycle-type count alone would not do so.

To compute \(F(r)\), choose disjoint supports for the selected cycles.
Each support has exactly one unoriented cyclic order. A support containing
two neighbours of \(\beta\) forces a fixed point of \(\rho\) and can
be rejected immediately. In particular, selected cycles longer than
\(\lfloor N/2\rfloor\) are impossible. The selected cycles also force
target arcs from \(\beta^{-1}(x)\) to \(\omega(x)\). A closed target
cycle before all \(N\) arcs have been placed makes a completion impossible.

Let \(E\) be the complement of the selected supports. Following the
forced target paths induces a permutation \(\phi\) on \(E\). If
\(\sigma\) is the still-unassigned restriction of \(\omega\) to \(E\),
the contracted target is \(\sigma\phi\). Let \(\lambda\) be the
cycle type of \(\sigma\), \(\mu\) that of \(\phi\), and \(q=|E|\).
The number of completions is the character sum

\[
K(\lambda,\mu)=\frac{|C_\lambda|}{q}
  \sum_{a=0}^{q-1}
  \frac{(-1)^a\chi^{(q-a,1^a)}(\lambda)
                    \chi^{(q-a,1^a)}(\mu)}{\binom{q-1}{a}}.
\]

This is the class-product character formula for the coefficient of the
full-cycle conjugacy class: only hook characters are nonzero on a full
cycle, their values there are \((-1)^a\), and their degrees are
\(\binom{q-1}{a}\). See
[Harvard's notes on characters and counting factorizations](https://abel.math.harvard.edu/~elkies/M263.21/index.html).
Here \(|C_\lambda|=q!/\prod_j j^{m_j}m_j!\). The hook characters are
the coefficients of

\[
\sum_{a=0}^{q-1}\chi^{(q-a,1^a)}(\lambda)t^a
   =\frac{\prod_{k\in\lambda}(1-(-t)^k)}{1+t}.
\]

The calculation uses exact integer arithmetic after clearing the binomial
denominators. Completion counts are reused for equal \(\mu\). When
\(E\) is empty, the selected cycles contribute one precisely when their
forced target is a full cycle; no character sum is needed.

## Nonidentity rotations

Set \(d=\gcd(N,\nu)\) and \(L=N/d\). Commutation with
\(\beta^\nu\) is equivalent to commutation with \(\beta^d\).
Because \(\rho=\omega\beta\), the same holds for \(\rho\).
Write a symbol as \(x=a+db\), with \(0\le a<d\), \(0\le b<L\).
Every permutation in this centralizer has the form
\(\rho(a,b)=(p(a),b+t_a\pmod L)\).

Such a \(\rho\) is an \(N\)-cycle exactly when \(p\) is a
\(d\)-cycle and \(\gcd(\sum_a t_a,L)=1\). Enumerate only these
choices, form \(\omega=\rho\beta^{-1}\), and classify its annotated
cycle type. This imposes realizability by construction. There are
\((d-1)!L^{d-1}\varphi(L)\) choices. The fixed-set counts depend only
on \(d\), and there are \(\varphi(N/d)\) rotation indices with that
greatest common divisor.

## Twisted reflections

Fix \(f=f_\nu\) and set \(h=f\beta\). The condition
\(f\omega^{-1}f=\omega\) is equivalent to \(q=\omega f\) being
an involution. Its target is \(\rho=qh\).

Draw the transpositions of the two involutions \(q\) and \(h\) as
edges of two colors. Each component is an alternating path or cycle.
On a path, \(qh\) has one cycle on its vertices; on an alternating
cycle it has two. Therefore \(qh\) is an \(N\)-cycle exactly when
the whole diagram is one alternating path.

The involution \(h\) has one fixed point for odd \(N\), or zero or
two for even \(N\). Enumerate the orders and directions of its
transposition edges along the path and join successive edges with
\(q\)-edges. Anchor the traversal at an \(h\)-fixed endpoint when one
exists. Otherwise identify the two reverse traversals by requiring the
first endpoint to be smaller than the last. The resulting
\(\omega=qf\) is both realizable and fixed by the twisted reflection.
Classify it directly from its permutation array.

For odd \(N\), every reflected rotation has the same fixed-set count.
For even \(N\), there are two reflection classes, with \(N/2\)
elements each. These counts and the rotation counts are cached by domain
size and symmetry, across all annotated types.

## Counting and validation

The implementation supports \(2\le N\le30\). It parallelizes support
choices and symmetry-constrained enumeration on the generator's CPU pool.
It does not generate or decode encoding words, run a sorting search, or
read certificates. Some constrained permutations and selected unoriented
supports are still enumerated; this is not a closed formula for every term.

## Active Eriksson route

The full endpoint cover has 143 even fixed-point-free annotated types on
16 symbols with budget 8. The Java Burnside counter independently
regenerated all 143 rows in the emitter and checker modules: 14,098,353,328
classes in total, 11,523,939,269 outside elementary diameter closures.
The active cover imposes no stricter 3-norm cutoff. Fixed points must be
contracted before using this fixed-point-free census.

The complete emitter census retains 0 unoriented and 8 oriented classes; 2 are support-cleared, leaving **6 direct root classes**. Independent full-domain regeneration agrees on every partition.

The three inverse waves cover both `(1,2,2)` and `(0,3,2)` paths on both
endpoints. Class counts are separate from witnesses within 8 moves at
endpoints and 11 moves at roots. See [the source paper](td20-reduction-count.tex)
and [validation](VALIDATION.md) for the checked scope and remaining work.

Small-domain tests compare Burnside with direct generation through nine
symbols, including empty types and both even-domain reflection classes.
Counting remains separate from sorting certificates and final inverse coverage.
