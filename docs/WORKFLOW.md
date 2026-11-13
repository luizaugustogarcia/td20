# Computation workflow

Totally unoriented roots now participate in joint-2 selection when they contain
even-length cycles. All-odd totally unoriented roots have no qualifying first
move; totally unoriented successors cannot supply the second gain. Two-step
prefixes must have gains (1,2) or (1,3). Let U be the full unoriented census and
R its residual count. The active direct obligation count is R + O, with O = J - C.
All 83 R counts are independently checked: R = 124,395,153 and R + O = 2,146,972,300.
Emission and proof checking reject missing or pending counts.

Count R with the Java command CountTD20UnorientedResiduals. Its per-partition
reports are versioned, deterministic resume boundaries. Use --publish to update
the explicit paper rows after counting; --n supports small-domain checks, which
cannot be published to the production inventory. Run the command through JShell using the emitter classpath. Generated artifacts remain untracked.

The route is specified in [the paper](td20-reduction-count.tex). Counting,
sorting, and proof verification are separate stages. The completed direct root
count is 2,146,972,300. The additional final reconstruction count is not yet known.

## 1. Validate the mathematical inventory

~~~bash
tools/td20 test
tools/td20 inventory
tools/td20 structure
~~~

These CPU commands check the 83 root partitions, 214 endpoint annotations,
104 oriented-3 endpoint closures, the joint-move predicates, and small-domain
generation/reconstruction tests. A structural check does not certify sorting.
The checker uses its own inventory and generation code.

## 2. Count the generated classes

~~~bash
tools/td20 count-r '-Dexec.args=--publish'
tools/td20 count -Dexec.args=--phase=roots
tools/td20 count -Dexec.args=--phase=endpoints
~~~

Independently regenerate every R count using the checker and JShell:

~~~bash
tools/td20 build
jshell --class-path "checker/target/classes:$(cat checker/target/jshell-classpath)" \
  tools/audit/CheckUnorientedResidualCounts.jsh
~~~

The audit checks every paper row and writes versioned partition reports in
data/unoriented-joint-residuals-checker-v1/. It reads no emitter artifact.
Count checking does not discharge sorting witnesses or inverse obligations.

Counting uses all available CPU cores by default and performs no sorting
search. For each root partition it checks the totally unoriented count,
the constrained oriented count before support clearing, the cleared subset,
and the remaining oriented count against the paper. Endpoint counting uses
the full annotated inventory. The Java count-artifact workflow in
[the operations guide](OPERATIONS.md) publishes independently reconciled counts.

## 3. Emit roots and endpoints

On a configured CUDA machine:

~~~bash
tools/td20 emit -Dtd20.args=--phase=roots
tools/td20 emit -Dtd20.args=--phase=endpoints
~~~

All direct roots use budget eleven and require a persisted complete witness or
known-diameter closure. Endpoint types with an oriented 3-cycle close by
1 + TD(14) = 9. For the other types, the emitter replays available witnesses,
then tries a bounded CPU prefix before searching on GPU within nine moves, checking the known-diameter exit at
every depth. Valid TD(18)
witnesses may be imported through --td18-db=PATH; every reused witness is
replayed. An endpoint search miss becomes a residual for final reconstruction,
not a proved lower bound. A missing eleven-move root result remains unresolved.

## 4. Resolve endpoint residuals at the end

~~~bash
tools/td20 avoidance
~~~

Regenerate endpoint classes without a nine-move discharge. Restore two
fixed points and reverse the second move, search deduplicated 20-symbol
predecessors directly on GPU within budget ten and persist successful discharges, then restore one fixed point and reverse
the first move. Each remaining deduplicated root needs a eleven-move closure
or complete witness. All gap distributions and admissible supports are
covered, including moves within even-length cycles. Missing obstruction
markers cannot omit obligations.

Inverse generation runs across the available CPU cores; intermediate and
root searches use the configured td20.twistedBraceletWorkers pool with
bounded queues. Each wave globally deduplicates its reconstruction keys, then
replays previously stored witnesses through multiGet batches before sending
each cache miss through a CPU sorting prefix for at most the configured
reconstruction deadline. A prefix miss or timeout sends the untouched class
and full budget to GPU search. The former CPU helper scan is not used. Default
buffers hold 1,048,576 reconstruction keys and 262,144 lookup classes. A
sealed intermediate or root checkpoint can resume through tools/td20
avoidance with --resume-work=PATH; see the operations guide for the checked
inventory and fresh-work requirements.

The default emit phase is all: roots, endpoints, then final reconstruction.
Rerunning regenerates the selected scope and reuses valid stored witnesses.

## 5. Independently verify the completed database

~~~bash
tools/td20 verify
~~~

The CPU checker independently regenerates all required classes and inverse
waves, checks counts, replays stored complete witnesses and diameter closures.
An absent, malformed, over-budget, or unresolved required result prevents
completion. Sidecar files and checksums are not proof inputs.
A phase or range selection proves only its selected scope. Only the complete
report meeting [every exit criterion](RESEARCH_PLAN.md) permits the
TD(20) = 11 conclusion.

The documented scheduling exception in TD(20) applies the same ten-millisecond
CPU prefix deadline to roots, endpoints, and reconstructed classes through
td20.cpuPrefixMillis. The source TD(18) applies a deadline to reconstruction.
Misses and timeouts preserve the unchanged class and full GPU budget.
