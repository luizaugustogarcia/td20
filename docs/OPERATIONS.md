# Computational operations

CPU work requires JDK 21, Maven, Bash, and Python 3. Runtime native sources
are CUDA; standalone C++ tools are independent counting audits.
See [workflow](WORKFLOW.md) and [proof criteria](RESEARCH_PLAN.md).

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
python3 -B -m unittest discover -s tools/audit -p 'test_*.py'
python3 -B tools/audit/check_td18_machinery.py --reference ../td18
tools/td20 inventory
~~~

There are 83 root partitions and 214 endpoint annotations, including 104
elementary closures. Structural checks accept explicit pending counts.

## Counting

~~~bash
python3 -B tools/audit/continue_joint2_counts.py --resume
tools/td20 count-u '-Dexec.args=--output=data/joint2-reduction-audit/unoriented-v1.json'
python3 -B tools/audit/run_java_joint_counts.py --resume
tools/td20 count-e '-Dexec.args=--output=data/joint2-reduction-audit/endpoints-v1.json'
~~~

The continuation detects CPU concurrency, locks outputs, validates complete
inventories, publishes rows, reconciles totals, runs tests, and builds TeX.
Root counting processes each partition with the bounded adaptive prefix
scheduler on every CPU available to the JVM, including the final partition.
Completed long-cycle prefixes that cannot yield an oriented residual are
discarded; totally unoriented roots remain separate obligations counted by U.
Whole partitions are atomic resume boundaries; unfinished Java partitions
restart. Correctly versioned native shard unions may be retained. Superseded
route formats are rejected. Selection-independent U data may be passed with
--unoriented=PATH. Do not relabel old residual or endpoint artifacts.
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

The database schema is joint2-selection-v1. Superseded databases and
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
