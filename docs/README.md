# Documentation map

[The source specification](td20-reduction-count.tex) defines the joint-2 route
toward the objective `TD(20) = 11`, the 83-partition root inventory, the 214
endpoint annotations, and both inverse waves. The lower bound is already
established by the reverse permutation; the route targets its matching upper
bound. Any generated PDF is a reading copy, not a computational certificate.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Exit criteria and pending proof artifacts. |
| [WORKFLOW.md](WORKFLOW.md) | Stage order, commands, and scope of each result. |
| [OPERATIONS.md](OPERATIONS.md) | CPU/GPU configuration, certificate format, import, and resumption. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Mathematical definitions, references, and implementation entry points. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module responsibilities and verification boundaries. |
| [td20-burnside-counting.md](td20-burnside-counting.md) | Burnside formulas, count-artifact requirements, and independent checks. |

Update the source specification first when changing the mathematical route. Recheck its
coverage and inventory independently, then align implementation and guides.
A documentation/code disagreement is a failed consistency check, not a choice
of one source over the other. Generated computational artifacts stay untracked.
