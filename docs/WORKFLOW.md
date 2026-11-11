# Computation workflow

[The source specification](td20-reduction-count.tex) defines the route toward
the project objective `TD(20) = 11`. The reverse-permutation formula already
establishes `TD(20) >= 11`; inventory validation, class counting, sorting, and
proof verification are the separate stages needed for the matching upper
bound. A completed count does not certify a sorting bound.

## 1. Validate the finite route

~~~bash
tools/td20 test
tools/td20 inventory
tools/td20 structure
~~~

The inventory must contain exactly 83 fixed-point-free even root partitions
on 21 symbols and 104 endpoint annotations on 16 symbols. Structural tests
must cover immediate gains of at least two, the two-move closure `(1,3)`, the
three favorable triples, and the totally-unoriented stop at every depth. The
checker owns independent inventory and routing implementations.

## 2. Produce exact count artifacts

Use the TD(18) Java Burnside counter for unoriented roots and endpoints,
and the constrained Java joint generator with joint-3 predicates for oriented
residuals. Worker detection, bounded queues, and prefix scheduling match TD(18).
The default CPU concurrency follows the processors visible to the JVM.
Native sorting sources remain CUDA.

~~~bash
python3 -B tools/audit/continue_joint3_counts.py
~~~

To preserve completed artifacts and resume unfinished counting:

~~~bash
python3 -B tools/audit/continue_joint3_counts.py --resume
~~~

The continuation validates U, counts missing joint partitions, counts all 104
endpoint annotations, combines and publishes the final table, runs CPU tests,
and builds the paper. It invokes no sorting search or certificate emission.
Completed native U and J audits can be retained as independent artifacts;
resumed CPU computation is Java. Provenance remains explicit. The historical
native-v1 directory name preserves the existing cache.

The separate counting commands are:

~~~bash
tools/td20 count-u '-Dexec.args=--output=data/joint3-reduction-audit/unoriented-v1.json'
python3 -B tools/audit/run_java_joint_counts.py --resume
tools/td20 count-e '-Dexec.args=--output=data/joint3-reduction-audit/endpoints-v1.json'
~~~

At every recursive branch, the active worker either visits the child locally
or transfers a snapshot to the shared bounded queue. This decision is repeated
throughout generation and Burnside traversal, so an expensive remaining subtree
can replenish the queue after other work finishes. The same utility handles
inverse supports and the independent checker. Completion joins every descendant
and propagates failures. Snapshot state is private; immutable tables are shared.

Java partition format td20-joint3-residual-count-v3 records its implementation
and scheduling scheme. Whole partitions are atomically published as resume
boundaries. Complete legacy shard groups are validated before reuse. Missing
partitions and incomplete groups cannot supply final rows. The continuation
updates J/C/O as each complete partition arrives; totals require all 83 rows.
The combined summary checks J=C+O and every deterministic key. A completed
count does not certify a sorting bound.

## 3. Emit direct roots and endpoints

On a configured CUDA machine, emit all totally unoriented roots and oriented
joint-3 residuals not discharged by the explicit support clearings. Every
direct root has budget 11. Endpoints have budget 8; the 52 annotations with an
oriented 3-cycle use their elementary closure. Imported endpoint witnesses are
replayed before use.

~~~bash
tools/td20 emit -Dtd20.args=--phase=roots
tools/td20 emit -Dtd20.args=--phase=endpoints
~~~

A search miss is an unresolved upper-bound obligation, not a lower bound.

## 4. Resolve endpoint residuals in three inverse waves

~~~bash
tools/td20 avoidance
~~~

Regenerate every endpoint without an eight-move discharge. Preserve the
`(1,1,3)` and `(1,2,2)` tags, because their inverse gains and intermediate
domains differ. Reverse the third move to obtain 19- or 18-symbol classes with
budget 9. Reverse the tagged second move to obtain 20-symbol classes with
budget 10. Reverse the first gain-one move to obtain roots with budget 11.
Each wave globally deduplicates classes but retains the union of tags. Every
gap, support, exact forward gain, and odd-cycle increase is replayed.

Checked results may stop a path at any wave. Unresolved classes propagate to
the next wave; every unresolved final root prevents completion. Missing
obstruction markers cannot omit work.

## 5. Independently verify the database

~~~bash
tools/td20 verify
~~~

The CPU checker independently regenerates all 83 root rows, all 104 endpoint
annotations, and the three inverse waves. It replays every complete witness and
known-diameter closure under the budget for that wave. Sidecars and checksums
are not proof inputs. A phase or range selection verifies only that scope.

Only a full successful report satisfying [the research plan](RESEARCH_PLAN.md)
establishes the TD(20) upper bound. Combining it with the independently
established [reverse-permutation lower bound](../../td/docs/paper/main.tex)
then proves `TD(20) = 11`. This repository never infers that lower bound from
class counts.
