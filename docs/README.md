# TD(20) proof documentation

[The source paper](td20-reduction-count.tex) is authoritative for the active
Eriksson-plus-third-move route.

The computer-assisted proof of **TD(20) = 11** is complete. Full-scope
independent verification succeeded on 22 November 2026, including all 83 root
partitions, 143 endpoint annotations and 51,456,682 final residual roots,
with zero unresolved obligations. The [reading PDF](td20-reduction-count.pdf)
presents the proof.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Completed evidence ledger and requirements for reproducing the proof. |
| [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md) | Successful full-scope TD20 verification, configuration, timings and artifact locations. |
| [TD18_PORT.md](TD18_PORT.md) | Complete commit-by-commit port inventory and TD20 adaptations. |
| [WORKFLOW.md](WORKFLOW.md) | Count, publish, emit, reconstruct and verify via JShell. |
| [OPERATIONS.md](OPERATIONS.md) | Explicit budgets, CPU/GPU configuration, persistence and resume. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Independent modules, generators and three inverse waves. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Pair conventions, encoding, symmetry and counting. |
| [td20-burnside-counting.md](td20-burnside-counting.md) | Burnside derivation and the complete endpoint census. |
| [RECONSTRUCTION_OPTIMIZATION.md](RECONSTRUCTION_OPTIMIZATION.md) | Experimental reconstruction alternatives, measurements, and required coverage safeguards. |

Update the paper before changing the route, then align both implementations,
counts, tests, guides and completion artifacts. Generated databases and work sets remain
untracked, including completed execution logs and settings.
A source/code disagreement must be identified explicitly.

[VALIDATION.md](VALIDATION.md) defines validation commands, artifact locations
and the independent verification record for the reference simulation.
