# Computational workflow

Totally unoriented roots now participate in joint-2 selection when they contain
even-length cycles. All-odd totally unoriented roots have no qualifying first
move; totally unoriented successors cannot supply the second gain. Two-step
prefixes must have gains (1,2) or (1,3). Let U be the full unoriented census and
R its residual count. The active direct obligation count is R + O, with O = J - C.
All 83 R counts are complete: R = 124,395,153 and R + O = 2,146,972,300.
Independent full-inventory count verification agrees in every row. Emission and proof
checking reject missing or pending counts.

Count R with the Java command CountTD20UnorientedResiduals. Its per-partition
reports are versioned, deterministic resume boundaries. Use --publish to update
the explicit paper rows after counting; --n supports small-domain checks, which
cannot be published to the production inventory. Run the command through Maven
or JShell using the emitter classpath. Generated artifacts remain untracked.

[The source specification](td20-reduction-count.tex) defines joint-2 coverage.
TD(18) = 10 is an independently checked premise. TD(20) = 11 is pending
[all proof exit criteria](RESEARCH_PLAN.md).

## 1. Validate small domains and inventory

~~~bash
mvn -pl emitter -am test
mvn -pl checker -am test
tools/td20 inventory
~~~

Inventory checks cover all 83 root partitions and 214 endpoint annotations.
They accept explicit pending counts and do not check sorting certificates.

## 2. Generate fresh counts

~~~bash
tools/td20 count-r '-Dexec.args=--publish'
~~~

Independently regenerate every R count using the checker and JShell:

~~~bash
mvn -pl checker -am compile
jshell --class-path checker/target/classes tools/audit/CheckUnorientedResidualCounts.jsh
~~~

The audit checks every paper row and writes versioned partition reports in
data/unoriented-joint-residuals-checker-v1/. It reads no emitter artifact.
Count checking does not discharge sorting witnesses or inverse obligations.

The Java command detects CPU concurrency, counts the unoriented residual stream
using the constrained prefix generator, and writes atomic per-partition reports
in data/unoriented-joint-residuals-v1/. Existing reports are strictly validated
before reuse. Missing source counts remain pending until published. U and J/C/O
retain their previous scopes; the new direct count is R + O. Counting proves
set size, never its sorting bound.

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
