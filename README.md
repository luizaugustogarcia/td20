# Toward TD(20) = 11

This repository contains the mathematical argument, Java/CUDA certificate
emitter, and independent Java checker for a prospective computer-assisted proof
that TD(20) = 11. The established reverse-permutation lower bound is 11;
**the matching upper bound remains conditional on checked computational coverage**.
TD(16) = 9 and TD(18) = 10 are established results using the same computational
methodology and are explicit completed premises of this project.
[TD18's independent reference verification](https://github.com/luizaugustogarcia/td18/blob/22042d8d2274ce05ce1c7868cedab66937eb3ebe/docs/REFERENCE_SIMULATION.md)
completed on 16 November 2026 with full regenerated coverage and exit zero.
[The source paper](docs/td20-reduction-count.tex) specifies the proof route.

The active Eriksson-plus-third-move route searches both the current and target
cycles. Two transpositions produce three fixed symbols, then a third produces
at least two more. Gain-zero first moves are included. Moving either endpoint
is justified by the triangle inequality; it does not add a class equivalence.

The inventory has **83 root partitions on 21 symbols** and **143 endpoint
annotations on 16 symbols**. All 83 emitter root-count rows are published:
U3 = 0 retained unoriented roots, O3 = 8 retained oriented roots and C3 = 2
support-cleared roots, leaving six direct obligations. The first 24 independent
root-count rows agree; 59 rows remain to check. Both modules independently agree
on all 143 endpoint rows:
14,098,353,328 classes, including 11,523,939,269 outside elementary closures.
Historical joint-2 counts and TD18 results cannot supply TD20 coverage.
Direct roots require budget 11; endpoints require
budget 8. Endpoint misses generate three inverse waves, including both `(1,2,2)`
and `(0,3,2)` branches on both endpoints, with budgets 9, 10 and 11.
Missing obstruction markers cannot suppress any obligation.

JDK 21, Maven, Bash and JShell are the baseline:

```bash
bash -ic './tools/td20 build'
bash -ic './tools/td20 test'
bash -ic './tools/td20 inventory'
bash -ic './tools/td20 count-roots'
bash -ic './tools/td20 audit-roots'
bash -ic './tools/td20 count-endpoints'
bash -ic './tools/td20 audit-endpoints'
bash -ic './tools/td20 endpoint-table'
bash -ic './tools/run-local-emitter.sh --build'
bash -ic './tools/run-local-emitter.sh'
bash -ic './tools/run-local-checker.sh --build'
bash -ic './tools/run-local-checker.sh'
```

The maintained launchers detect available CPU cores and memory limits and record
each run's configuration, timings and status. Their resource limits and CUDA
architecture are configurable. The generic GPU build retains SM 120; the local
launcher defaults to SM 89. Neither is evidence of a completed TD20 run.
Successful CPU computations do not initialize CUDA. The checker has no emitter
artifact dependency and independently regenerates obligations before replay.
Both CPU suites exhaustively compare retained root class sets for every domain
of three through eight symbols with an independent arbitrary-support oracle.
Full 21-symbol independent counting and witness replay remain required for the
planned fresh verification run.

Generated databases, count reports, checkpoints, certificates and logs remain
untracked. See [workflow](docs/WORKFLOW.md), [operations](docs/OPERATIONS.md),
[architecture](docs/ARCHITECTURE.md), [conventions](docs/KNOWLEDGE.md),
[validation](docs/VALIDATION.md), and the [39-commit port ledger](docs/TD18_PORT.md).
Every exit criterion in [the research plan](docs/RESEARCH_PLAN.md) requires a
checked artifact before the upper bound can be claimed.
