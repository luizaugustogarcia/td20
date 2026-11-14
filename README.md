# TD(20) by Eriksson reduction and a third transposition

The active route searches both the current and target cycles: two transpositions
produce three fixed symbols, then a third produces at least two more. Gain-zero
first moves are included. The original target need not be held fixed throughout
this triangle-inequality argument. The proof specification is
[the source paper](docs/td20-reduction-count.tex).

The complete emitter census retains 0 unoriented and 8 oriented classes; 2 are support-cleared, leaving **6 direct root classes**. Independent full-domain regeneration agrees on every partition.

The endpoint cover contains all 143 even fixed-point-free annotated types on 16 symbols, with budget 8.
TD(18) = 10 is the explicit completed premise supplied for this project; the TD18 repository is not asserted to have completed its proof.

A counting result is separate from a sorting certificate. Every remaining direct
root needs a checked result within 11 moves. Every non-elementary endpoint
needs a result within 8 moves or complete inverse coverage. Actual endpoint
misses generate three inverse waves, including both `(1,2,2)` and `(0,3,2)`
branches on both endpoints. Missing obstruction markers cannot suppress work.
A final reconstructed root always needs budget 11.

The active route does not establish TD(20) = 11 until the artifacts in
[the research plan](docs/RESEARCH_PLAN.md) are complete. The checker regenerates
all obligations and replays intrinsic witnesses; emitter checkpoints and sidecar
manifests are not proof inputs.

JDK 21, Maven, Bash and JShell are the baseline:

```bash
tools/td20 build
tools/td20 test
tools/td20 inventory
tools/td20 count-roots
tools/td20 audit-roots
tools/td20 count-endpoints
tools/td20 audit-endpoints
tools/td20 emit --phase=all
tools/td20 verify --phase=all
```

Java entry points run through JShell, using all JVM-visible CPUs by default.
Build CUDA with `tools/td20 build-gpu` before a GPU sorting run. CPU counts,
checks and successful CPU prefixes do not initialize CUDA. Generated databases,
logs, checkpoints and count reports remain untracked. The checker has no emitter
artifact dependency. See [workflow](docs/WORKFLOW.md),
[operations](docs/OPERATIONS.md), [architecture](docs/ARCHITECTURE.md), and
[mathematical conventions](docs/KNOWLEDGE.md), and [validation](docs/VALIDATION.md).
