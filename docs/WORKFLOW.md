# Computational workflow

[The source specification](td20-reduction-count.tex) defines joint-2 coverage.
TD(18) = 10 is an independently checked premise. TD(20) = 11 is pending
[all proof exit criteria](RESEARCH_PLAN.md).

## 1. Validate small domains and inventory

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
python3 -B -m unittest discover -s tools/audit -p 'test_*.py'
tools/td20 inventory
~~~

Inventory checks cover all 83 root partitions and 214 endpoint annotations.
They accept explicit pending counts and do not check sorting certificates.

## 2. Generate fresh counts

~~~bash
python3 -B tools/audit/continue_joint2_counts.py --resume
~~~

The continuation detects CPU concurrency, runs Java counts, validates complete
partition results, publishes rows, reconciles endpoint totals, and runs tests
and TeX. It performs no sorting. Outputs use data/joint2-reduction-audit/;
superseded route formats are rejected. The completed selection-independent
U artifact may be passed explicitly with --unoriented=PATH. J/C/O and endpoint
counts require fresh joint-2 data. Direct obligations total U + O, with O = J - C.
Counting proves set size, never its sorting bound.

## 3. Emit direct roots and endpoints

~~~bash
tools/td20 emit -Dtd20.args=--phase=roots
tools/td20 emit -Dtd20.args=--phase=endpoints
~~~

On a configured CUDA machine, roots use budget eleven and endpoints nine.
The 104 oriented-3-cycle annotations close structurally. Every class in the
other 110 annotations needs a result or inverse coverage. Root emission
rejects pending source counts. Imported witnesses are individually replayed.

## 4. Reconstruct unresolved endpoints

~~~bash
tools/td20 avoidance
~~~

Regenerate all endpoints without nine-move discharges. Reverse gain two to
20-symbol budget-ten classes, then gain one to 21-symbol budget-eleven roots.
Each wave enumerates every gap and support, checks forward gain and odd-cycle
increase, and globally deduplicates on disk. Checked results stop paths;
unresolved classes propagate. Search misses are upper-bound obligations.

## 5. Independently verify

~~~bash
tools/td20 verify
~~~

The CPU checker independently regenerates all roots, endpoints, and both
inverse waves. Missing obstruction markers cannot suppress work. Every result
is replayed at its active budget. Only a full successful report satisfying
the research plan establishes the upper bound; partial scopes certify only
their selected classes.
