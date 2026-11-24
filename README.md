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
checker run, its configuration and recorded results.

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
Direct roots require budget 11; endpoints require
budget 8. Endpoint misses generate three inverse waves, including both `(1,2,2)`
and `(0,3,2)` branches on both endpoints, with budgets 9, 10 and 11.
Missing obstruction markers cannot suppress any obligation.

The certificate database archive is an uncompressed tar file in the
public S3 bucket `td20` (AWS region `us-east-2`):
[certificate.tar](https://td20.s3.us-east-2.amazonaws.com/certificate.tar).
The archive is 89,848,709,120 bytes (83.68 GiB) and contains the `certificates/`
directory. Extract it with
`tar -xf certificate.tar` and use the independent checker described in
[the execution instructions](docs/RUNNING.md) to regenerate obligations and
replay witnesses. Download access requires no AWS account. A
[SHA-256 checksum file](https://td20.s3.us-east-2.amazonaws.com/certificate.tar.sha256)
is available; verify a downloaded archive with
`sha256sum -c certificate.tar.sha256`.

JDK 21, Maven, Bash and JShell are the baseline. See
[direct build and execution instructions](docs/RUNNING.md) for compilation,
CPU tests, CUDA builds, emission and independent verification. Record settings
and logs explicitly.

Successful CPU computations do not initialize CUDA. The checker has no emitter
artifact dependency and independently regenerates obligations before replay.
Both CPU suites exhaustively compare retained root class sets for every domain
of three through eight symbols with an independent arbitrary-support oracle.
Full 21-symbol independent regeneration and witness replay completed in
`checker-20261122T183611Z-32945`.

Generated databases, count reports, checkpoints, certificates and logs remain
untracked. See [running](docs/RUNNING.md), [architecture](docs/ARCHITECTURE.md),
[conventions](docs/KNOWLEDGE.md), and [validation](docs/VALIDATION.md).
For illustrated internal execution, read [the visual guide](docs/EXECUTION.md),
[the emitter walkthrough](docs/EMITTER_EXECUTION.md) and
[the checker walkthrough](docs/CHECKER_EXECUTION.md).
[The proof completion record](docs/RESEARCH_PLAN.md) maps all five completion
criteria to checked evidence and retains the requirements for reproduction.
