# Proving TD(20) = 11 by joint selection of three reductions

The objective of this repository is a checked proof that `TD(20) = 11`. The
[reverse-permutation lower bound](../td/docs/paper/main.tex) already gives
`TD(20) >= 11`, independently of this computation. The project extends the
TD(18) joint-selection route to cyclic-target pairs on 21 symbols, treats
`TD(18) = 10` as an established premise, and targets the matching upper bound
`TD(20) <= 11`. The exact structural rule follows all
qualifying gain-one first moves, closes a gain-three second move, and otherwise
tests the favorable gain triples `(1,1,3)`, `(1,2,2)`, and `(1,2,3)`.
Totally unoriented states receive no structural reduction at any depth.

The two endpoint-producing triples reach fixed-point-free 16-symbol pairs with
eight moves left. Endpoint sorting failures are handled by three inverse waves:
reverse the tagged third move, reverse the tagged second move, and finally
reverse the gain-one first move. Direct roots have budget 11, the two
intermediate waves have budgets 9 and 10, and endpoints have budget 8.

Exact class counting is a separate CPU-only stage. The root inventory has 83
fixed-point-free even partitions and the endpoint cover has 104 annotations.
The source table publishes completed count components as their versioned
artifacts become available. Final class totals require all artifacts and the
source table to agree row for row. Counts
alone do not establish the sorting bound. The equality is not proved by this
repository until the independent checker accepts every sorting certificate and
all other exit criteria in the research plan.

The completed native Burnside count gives `U = 12,704,114,061` totally
unoriented root classes. All 83 U values are recorded in the source table.
The counting workflow publishes J/C/O as complete partition results arrive
and reconciles endpoint counts before producing the final summary.

## Start here

- [Source specification](docs/td20-reduction-count.tex)
- [Research plan](docs/RESEARCH_PLAN.md)
- [Workflow](docs/WORKFLOW.md)
- [Mathematical knowledge](docs/KNOWLEDGE.md)
- [Operations](docs/OPERATIONS.md)
- [Architecture](docs/ARCHITECTURE.md)

JDK 21, Maven, Bash, and Python 3 are the CPU baseline. The implementation is
Java with native CUDA sorting sources. Generation, Burnside counting, search,
worker scheduling, persistence, and independent checking use the TD(18)
machinery. The TD(20) domains, budgets, joint-3 predicates, and third inverse
wave are the project-specific changes.
Existing standalone C++ counting artifacts remain independent audits.

~~~bash
tools/td20 test
tools/td20 inventory
tools/td20 structure
~~~

| Path | Responsibility |
| --- | --- |
| `emitter/` | Direct class generation, sorting, and certificate emission. |
| `checker/` | Independent regeneration, inverse coverage, and witness replay. |
| `docs/` | Mathematical route, completion criteria, and operations. |
| `tools/` | Workflow launcher and independent counting audits. |

The checker does not depend on the emitter artifact. Generated count reports,
databases, checkpoints, logs, and certificates remain untracked. The sibling
`../td` repository is used only for the shared twisted-bracelet framework and
established mathematical conventions.

Licensed under the [Apache License, Version 2.0](LICENSE).
