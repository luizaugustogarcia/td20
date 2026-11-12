# Proving TD(20) = 11 by joint selection of two reductions

The established reverse-permutation lower bound is TD(20) >= 11. This project
uses joint-2 selection from the independently checked TD(18) project,
taking TD(18) = 10 as an established premise, to pursue TD(20) <= 11.
Equality is pending [the full proof exit criteria](docs/RESEARCH_PLAN.md).

Roots are fixed-point-free valid cyclic-target pairs on 21 symbols. Immediate
gain at least two closes using TD(18). Otherwise inspect every qualifying
gain-one first move and every second move creating at least two fixed points.
Gain three closes using TD(16) = 9; gain two reaches an 18-symbol endpoint
with nine moves left. Totally unoriented states receive no structural
reduction at either depth.

There are 83 root partitions and 214 endpoint annotations, including 104
with an elementary oriented-3-cycle closure. Unresolved endpoints undergo two
inverse waves: restore gain two on 20 symbols with budget ten, then gain one
on 21 symbols with budget eleven. Every gap and support is forward replayed.

The completed selection-independent count is U = 12,704,114,061 totally
unoriented root classes. The full 214-annotation endpoint cover contains
1,806,057,392,878 classes: 614,986,821,324 elementary closures and
1,191,070,571,554 classes requiring discharge. Root J/C/O counts are still
running; the source publishes each completed partition. Count artifacts are
in data/joint2-reduction-audit/. These endpoint counts precede witness search
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
python3 -B -m unittest discover -s tools/audit -p 'test_*.py'
~~~

See [the source specification](docs/td20-reduction-count.tex),
[workflow](docs/WORKFLOW.md), [operations](docs/OPERATIONS.md),
[architecture](docs/ARCHITECTURE.md), and [conventions](docs/KNOWLEDGE.md).
