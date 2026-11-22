# Validation of the TD20 computation

The active structural inventory has 83 root partitions and 143 endpoint
annotations. Counts and tests alone do not prove TD(20) = 11. The complete
required evidence is in [RESEARCH_PLAN.md](RESEARCH_PLAN.md); completed TD20
execution metadata belongs in [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md).
No TD18 emission or checker success is a TD20 verification result.

## CPU validation and inventories

Load the configured toolchain and run both required suites:

```bash
bash -ic 'mvn -pl emitter -am test'
bash -ic 'mvn -pl checker -am test'
bash -ic './tools/td20 build'
bash -ic './tools/td20 inventory'
```

The two modules independently compare small-domain root class sets, inverse gains
zero through three on both endpoints, canonical keys and replay. In each module,
`ActiveErikssonRouteTest.completeClassesAgreeWithIndependentMovesOnBothCyclesThroughEight`
enumerates every valid fixed-point-free input for each domain of three through
eight symbols and compares the full retained class set with an arbitrary-support
oracle moving the actual current and target cycles. Duplicate output classes
fail. Both suites passed these complete class-set checks, their serial/parallel
count comparisons and the 21-symbol reverse-exception test. These are explicit
unit-test scopes; exhaustive 21-symbol independent counting and full witness
replay still belong to the planned fresh proof run. Persistence
checks cover bounded queues, sharded sorted tables, scan remainders, malformed or
missing records, propagation of writer failures, interrupted handoffs and strongest
certificate retention. These tests establish their explicit scope.

Regenerate all active counts via JShell:

```bash
bash -ic './tools/td20 count-roots'
bash -ic './tools/td20 audit-roots'
bash -ic './tools/td20 count-endpoints'
bash -ic './tools/td20 audit-endpoints'
bash -ic './tools/td20 endpoint-table'
```

Root reports are versioned per-partition records in
`data/eriksson-root-counts-v1/` and `data/eriksson-root-counts-checker-v1/`.
Endpoint reports use `data/eriksson-endpoint-counts-v1/` and
`data/eriksson-endpoint-counts-checker-v1/`. Every route/domain/budget, partition,
annotation, count and elementary-closure flag must agree. Zero rows remain
explicit; missing or pending data stop proof verification. Historical joint-2
reports are incompatible with the active route.

## CUDA validation when configured

CPU tests do not load CUDA unless the test environment enables it. A configured
CUDA toolkit, compatible driver/device and built native library are required:

```bash
bash -ic 'TD20_BUILD_NATIVE_TESTS=ON ./tools/run-local-emitter.sh --build'
bash -ic 'ctest --test-dir emitter/target/native --output-on-failure'
bash -ic 'mvn -pl emitter -am test -Dtd20.gpuSmoke=true'
```

Use `TD20_CUDA_ARCHITECTURE` for the executing GPU; the local default is SM 89,
and the generic build retains SM 120. Native tests check exact slot allocation,
including caches and metadata. CUDA regression tests exercise diameter closures,
explicit exploration caps, timeouts, result validation and work accounting.
A skipped CUDA test is not a passed device check. Record the actual executed
scope and any unavailable device validation.

## Full independent verification

Close the emitter before transferring or checking its `certificates/` database.
Build the checker independently and use fresh work, with no phase or range
omissions:

```bash
bash -ic './tools/run-local-checker.sh --build'
bash -ic './tools/run-local-checker.sh'
```

The launcher records settings and logs under project-root `local-runtime/`
by default, outside Maven `target/` directories.
Alternatively, use `tools/td20 verify --phase=all --database=PATH --work=FRESH_PATH`
through the prepared JShell launcher. The full success marker is
`All regenerated obligations in the complete joint-selection scope are verified.`
and the process must exit zero. JShell exit alone is insufficient.

Direct roots require budget 11; endpoints require 8. Endpoint misses independently
regenerate the complete first inverse wave on 18 symbols with budget nine.
The checker replays the copied TD18 results from the TD20 database to derive
every second-level residual. Stored 16-symbol and 18-symbol obstruction markers
are informational; neither markers nor probe/import reports may omit work.
Imported-witness audits use explicit source, certificate, residual and report
paths, together with expected counts for that scope. The audit replays results
within budget nine and checks that residuals equal the missing classes.
Full endpoint regeneration and full TD20 verification remain required.

Every second-level residual regenerates both `(1,2,2)` and `(0,3,2)` paths
on both endpoints through budgets ten and eleven. Budget-ten states are
generation obligations: no sorting search or certificate lookup there prunes
their final roots. The checker must replay every final root at budget eleven
and reject missing results. Its final `inverse-coverage-v3.txt` records
the full after-two count, budget-nine closures, second-level residuals,
after-one states, final roots and zero unresolved roots. This report is an
output of verification, not an input that grants coverage.

No predicted obstruction list or extra 3-norm cutoff may reduce the scope.
Emitter checkpoints, manifests and checksums are not proof inputs.
Residual-expansion checkpoints carry an explicit mode and exact active input
counts; historical full-search batches cannot be substituted for them.

Keep databases, scratch work, count reports, logs and settings untracked. Record
the completed scope, configurations, measured timings and artifact locations in
the execution record, distinguishing emitter results from independently checked
coverage. Partial runs certify only their stated scope.
