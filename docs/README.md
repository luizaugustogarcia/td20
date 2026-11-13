# Documentation map

[The source paper](td20-reduction-count.tex) specifies the joint-selection
argument, complete 83-partition root inventory, endpoint coverage, and final
inverse reconstruction. Its [PDF](td20-reduction-count.pdf) is a reading copy,
not a computational certificate.

| Document | Responsibility |
| --- | --- |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Exit criteria and pending proof artifacts. |
| [WORKFLOW.md](WORKFLOW.md) | Stage order, commands, and scope of each result. |
| [OPERATIONS.md](OPERATIONS.md) | CPU/GPU configuration, certificate format, import, and resumption. |
| [KNOWLEDGE.md](KNOWLEDGE.md) | Mathematical definitions, references, and implementation entry points. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module responsibilities and verification boundaries. |
| [td20-burnside-counting.md](td20-burnside-counting.md) | Exact realizable-class counts and independent small-domain checks. |

Update the paper first when changing the mathematical route. Recheck its
coverage and inventory independently, then align implementation and guides.
A documentation/code disagreement is a failed consistency check, not a choice
of one source over the other. Generated computational artifacts stay untracked.
