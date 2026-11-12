# Proving TD(20) = 11 by joint selection of two reductions

Totally unoriented roots now participate in joint-2 selection when they contain
even-length cycles. All-odd totally unoriented roots have no qualifying first
move; totally unoriented successors cannot supply the second gain. Two-step
prefixes must have gains (1,2) or (1,3). Let U be the full unoriented census and
R its residual count. The active direct obligation count is R + O, with O = J - C.
All 83 R counts are complete: R = 124,395,153 and R + O = 2,146,972,300.
Independent full-inventory count verification agrees in every row. Emission and proof
checking reject missing or pending counts.

Count R with the Java command CountTD20UnorientedResiduals. Its per-partition
reports are versioned, deterministic resume boundaries. Use --publish to update
the explicit paper rows after counting; --n supports small-domain checks, which
cannot be published to the production inventory. Run the command through Maven
or JShell using the emitter classpath. Generated artifacts remain untracked.

The established reverse-permutation lower bound is TD(20) >= 11. This project
uses joint-2 selection from the independently checked TD(18) project,
taking TD(18) = 10 as an established premise, to pursue TD(20) <= 11.
Equality is pending [the full proof exit criteria](docs/RESEARCH_PLAN.md).

Roots are fixed-point-free valid cyclic-target pairs on 21 symbols. Immediate
gain at least two closes using TD(18). Otherwise inspect every qualifying
gain-one first move and every second move creating at least two fixed points.
Gain three closes using TD(16) = 9; gain two reaches an 18-symbol endpoint
with nine moves left. Totally unoriented roots with even-length cycles
participate; all-odd totally unoriented roots have no qualifying first move.
Totally unoriented successors cannot supply the required second gain.

There are 83 root partitions and 214 endpoint annotations, including 104
with an elementary oriented-3-cycle closure. Unresolved endpoints undergo two
inverse waves: restore gain two on 20 symbols with budget ten, then gain one
on 21 symbols with budget eleven. Every gap and support is forward replayed.

The completed selection-independent count is U = 12,704,114,061 totally
unoriented root classes. The full 214-annotation endpoint cover contains
1,806,057,392,878 classes: 614,986,821,324 elementary closures and
1,191,070,571,554 classes requiring discharge. All 83 root counts are complete:
J = 2,051,578,111, C = 29,000,964, and O = 2,022,577,147. The direct-root
count is R + O = 2,146,972,300. Joint-2 removes 12,579,718,908 former direct
unoriented obligations, or 99.02% of U. Count artifacts are in
data/joint2-reduction-audit/ and data/unoriented-joint-residuals-v1/.
These counts precede witness search
and can include unreachable endpoints. Existing witnesses and checked closures
can reduce explicit search; the actual unsolved residue for inverse
reconstruction remains unknown. Sorting certificates remain pending.

Java supplies CPU generation, counting, scheduling, persistence, sorting
prefixes, and independent verification. Runtime native sources remain CUDA.
Shared machinery follows TD(18), with TD(20) domain and budget adapters.
Generated databases, counts, checkpoints, logs, and certificates stay untracked.

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
tools/td20 inventory
~~~

See [the source specification](docs/td20-reduction-count.tex),
[workflow](docs/WORKFLOW.md), [operations](docs/OPERATIONS.md),
[architecture](docs/ARCHITECTURE.md), and [conventions](docs/KNOWLEDGE.md).
