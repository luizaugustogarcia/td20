# TD(20) proof documentation

[The source paper](td20-reduction-count.tex) is authoritative for the active
Eriksson-plus-third-move route.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Required checked evidence for the proof. |
| [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md) | Status and required configuration, timings and artifact locations for TD20 execution. |
| [TD18_PORT.md](TD18_PORT.md) | Complete commit-by-commit port inventory and TD20 adaptations. |
| [WORKFLOW.md](WORKFLOW.md) | Count, publish, emit, reconstruct and verify via JShell. |
| [OPERATIONS.md](OPERATIONS.md) | Explicit budgets, CPU/GPU configuration, persistence and resume. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Independent modules, generators and three inverse waves. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Pair conventions, encoding, symmetry and counting. |
| [td20-burnside-counting.md](td20-burnside-counting.md) | Burnside derivation and the complete endpoint census. |

Update the paper before changing the route, then align both implementations,
counts, tests, guides and completion artifacts. Generated databases and work sets remain
untracked, including completed execution logs and settings.
A source/code disagreement must be identified explicitly.

[VALIDATION.md](VALIDATION.md) defines validation commands, artifact locations
and the independent verification record for the reference simulation.
