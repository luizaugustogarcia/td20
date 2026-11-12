# Computational operations

Joint-2 includes totally unoriented roots with even-length cycles. All-odd
totally unoriented roots and all totally unoriented successors stop for the
reasons proved in the source paper. The active direct count is R + O; R counts
unoriented residuals. All 83 R counts are complete, with R = 124,395,153. See
[WORKFLOW.md](WORKFLOW.md) for Java counting and the versioned artifact formats.

CPU work requires JDK 21, Maven, and Bash. Runtime native sources
are CUDA; standalone C++ tools are independent counting audits.
See [workflow](WORKFLOW.md) and [proof criteria](RESEARCH_PLAN.md).

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
tools/td20 inventory
~~~

There are 83 root partitions and 214 endpoint annotations, including 104
elementary closures. Structural checks accept explicit pending counts.

## Counting

~~~bash
tools/td20 count-r '-Dexec.args=--publish'
tools/td20 count-u '-Dexec.args=--output=data/joint2-reduction-audit/unoriented-v1.json'
tools/td20 count-j '-Dexec.args=--output=data/joint2-reduction-audit/java-production-counts-v1.jsonl'
tools/td20 count-e '-Dexec.args=--output=data/joint2-reduction-audit/endpoints-v1.json'
~~~

The Java R counter detects CPU concurrency, validates its versioned partition
reports, resumes completed partitions, and optionally publishes the R rows.
The independent JShell audit in [WORKFLOW.md](WORKFLOW.md) checks every R count
without reading the emitter artifact. The U, J/C/O, and endpoint counters
retain their separate scopes; a count is not a sorting result.
Root counting processes each partition with the bounded adaptive prefix
scheduler on every CPU available to the JVM, including the final partition.
Completed long-cycle prefixes that cannot yield an oriented residual are
discarded; totally unoriented residual roots are separate obligations counted by R.
Whole partitions are atomic resume boundaries; unfinished Java partitions
restart. Correctly versioned native shard unions may be retained. Superseded
route formats are rejected. Do not relabel old residual or endpoint artifacts.
Root emission and proof verification reject pending paper counts.

## Emission and reconstruction

~~~bash
tools/td20 build-gpu
tools/td20 emit -Dtd20.args=--phase=roots
tools/td20 emit -Dtd20.args=--phase=endpoints
tools/td20 avoidance
tools/td20 avoidance -Dtd20.args=--resume-work=/path/to/joint2/work
tools/td20 verify
~~~

| Obligation | Symbols | Budget |
| --- | ---: | ---: |
| Direct root | 21 | 11 |
| Endpoint | 18 | 9 |
| Reverse gain-two second move | 20 | 10 |
| Reverse gain-one first move | 21 | 11 |

The database schema is joint2-selection-v2. Superseded databases and
checkpoints are rejected. Use fresh output paths; existing generated data
is preserved. --td18-db=PATH imports an external source read-only and replays
each witness within budget. Imports establish neither coverage nor counts.
Work is fresh and outside the database. Sealed joint-2 intermediate/root
checkpoints can resume into fresh work; preserve the source checkpoint.
The checker independently regenerates obligations.

CPU workers are detected automatically. Properties td20.twistedBraceletWorkers,
td20.reconstructionBufferKeys, td20.certificateLookupBatchSize, and
td20.certificateQueueCapacity control concurrency and bounded memory.
CUDA uses td20.gpuDevices, td20.gpuSlots, and td20.gpuBudgetMB.
Every CPU prefix has a general 10 ms wall-clock limit, configured with
td20.cpuPrefixMillis. This applies to roots, endpoints, and reconstruction.
The depth and support limits td20.cpuPrefixDepth and td20.cpuPrefixMaxTriples
can end a search earlier. A timeout or miss sends the unchanged full-budget
obligation to GPU.
CUDA tests run only when configured explicitly.

Storage estimates require actual compression measurements and inverse-wave
inventories.

## Pruning completed certificates

~~~bash
tools/td20 prune
tools/td20 prune '-Dexec.args=--replace-source'
~~~

The first command writes a fresh certificates-pruned database. The second
replaces certificates and deletes its superseded files only after full checks
pass. Use --source=PATH, --output=NEW_PATH, --paper=PATH, and --work=NEW_PATH
to select paths. Output and work must be fresh and separate from the source.
Both databases are locked against concurrent writers during pruning.

Pruning regenerates every current root, endpoint, and both inverse waves. It
copies the original typed witness or closure only when replay succeeds within
the requested budget. A second full check of the persisted copy records its
used classes independently; an exact comparison of keys and values rejects
any surplus or missing result. A versioned pruning-report-v1.txt records exact
record counts, byte sizes, completed checks, and surplusClasses=0. Missing
endpoint results still trigger inverse reconstruction without relying on old
obstruction markers. Failure before publication preserves the source.

Only this migration reader also accepts the immediately preceding certificate
route marker. It imports replayed results, never obsolete route counts,
obstruction markers, or checkpoints. Normal proof checking remains strict.
The retained classes are exactly those used by the current checker; this is
not a claim of a globally minimum certificate over alternative proof routes.
Generated databases, work directories, logs, and reports remain untracked.

## Independent checking and TeX

~~~bash
tools/td20 verify
mkdir -p /tmp/td20-paper-build
pdflatex -interaction=nonstopmode -halt-on-error -output-directory=/tmp/td20-paper-build docs/td20-reduction-count.tex
pdflatex -interaction=nonstopmode -halt-on-error -output-directory=/tmp/td20-paper-build docs/td20-reduction-count.tex
~~~

The checker rejects missing required results and replays closures against the
actual nonfixed remainder. Missing markers, sidecars, and checksums cannot
omit work. Partial scopes cannot prove TD(20) = 11. Artifacts remain untracked.
