# A computer-assisted proof of TD(20) = 11

This repository contains the mathematical argument, Java/CUDA certificate
emitter, and independent Java checker for the completed computer-assisted proof
that **TD(20) = 11**. Full-scope independent verification completed on
**22 November 2026**, establishing the upper bound of eleven transpositions.
The established reverse-permutation lower bound gives equality.
TD(16) = 9 and TD(18) = 10 are established results using the same computational
methodology and are explicit completed premises of this project.
[The source paper](docs/td20-reduction-count.tex) specifies the proof route.
[The reading PDF](docs/td20-reduction-count.pdf) presents the proof, and
[the execution record](docs/REFERENCE_SIMULATION.md) documents the successful
checker run, its configuration and retained evidence.

The checker completed all **83 root partitions** and **143 endpoint annotations**,
independently regenerated **4,010,256,622 budget-ten source classes**, and checked
**51,456,682 final residual root classes**, with **zero unresolved obligations**.
The run used fresh work, covered both inverse branches and both endpoints,
printed the full-scope application success marker, and exited zero.

The active Eriksson-plus-third-move route searches both the current and target
cycles. Two transpositions produce three fixed symbols, then a third produces
at least two more. Gain-zero first moves are included. Moving either endpoint
is justified by the triangle inequality; it does not add a class equivalence.

The inventory has **83 root partitions on 21 symbols** and **143 endpoint
annotations on 16 symbols**. All 83 emitter root-count rows are published:
U3 = 0 retained unoriented roots, O3 = 8 retained oriented roots and C3 = 2
support-cleared roots, leaving six direct obligations. The paper records all
143 endpoint rows with
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
architecture are configurable. The generic GPU build includes SM 89 and SM 120; the local
launcher defaults to SM 89.
Successful CPU computations do not initialize CUDA. The checker has no emitter
artifact dependency and independently regenerates obligations before replay.
Both CPU suites exhaustively compare retained root class sets for every domain
of three through eight symbols with an independent arbitrary-support oracle.
Full 21-symbol independent regeneration and witness replay completed in
`checker-20261122T183611Z-32945`.

Generated databases, count reports, checkpoints, certificates and logs remain
untracked. See [workflow](docs/WORKFLOW.md), [operations](docs/OPERATIONS.md),
[architecture](docs/ARCHITECTURE.md), [conventions](docs/KNOWLEDGE.md),
[validation](docs/VALIDATION.md), and the [39-commit port ledger](docs/TD18_PORT.md).
[The proof completion record](docs/RESEARCH_PLAN.md) maps all five completion
criteria to checked evidence and retains the requirements for reproduction.
