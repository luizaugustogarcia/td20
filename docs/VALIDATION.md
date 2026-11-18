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

The launcher records settings and logs under `target/local-runtime/` by default.
Alternatively, use `tools/td20 verify --phase=all --database=PATH --work=FRESH_PATH`
through the prepared JShell launcher. The full success marker is
`All regenerated obligations in the complete joint-selection scope are verified.`
and the process must exit zero. JShell exit alone is insufficient.

Direct roots require budget 11; endpoints require 8. Endpoint misses independently
regenerate the complete first inverse wave on 18 symbols with budget nine.
The checker replays the copied TD18 results from the TD20 database to derive
every second-level residual. Stored 16-symbol and 18-symbol obstruction markers
are informational; neither markers nor probe/import reports may omit work.
The completed reuse probe found 4,062,619 valid budget-nine results among
5,032,950 classes, leaving 970,331 residuals; the full checker must independently
regenerate its actual inventories and replay all required TD20 records.
The import into project-root `certificates/` is complete.
`tools/td20 audit-import --check-markers` independently replayed all
5,032,950 persisted source states, checked the exact 4,062,619/970,331
partition and residual-set equality, and required all residual markers.
Its completed report is
`fleet/production-td20-20261117/td18-after-two-reuse-v1/independent-import-scope-v1.txt`.
This audit covers the persisted import scope; full endpoint regeneration
and full TD20 verification remain pending. The active residual checkpoint
and importer report are in the sibling `residuals/` directory.

To repeat the import audit with a fresh report path, use the checker JShell
classpath:

```bash
bash -ic 'tools/td20 audit-import --check-markers --report=/tmp/td20-independent-import-scope-v1.txt'
```

The default inputs are the production run's complete persisted after-two wave,
the project `certificates/` database, and its imported second-level residual
wave. Override them with `--source=PATH`, `--certificates=PATH`,
`--residuals=PATH` and a fresh `--report=PATH`. The audit replays all results
within budget nine, validates every canonical fixed-point-free v3 state key and
empty value, and checks that the residual set equals every missing class.
It requires exactly 5,032,950 source classes, 4,062,619 budget-nine hits and
970,331 residuals. The optional marker check verifies installation without
selecting obligations. Its `td20-independent-imported-td18-scope-v1` report
explicitly excludes whole-endpoint regeneration and full TD20 proof
verification, and cannot replace `tools/td20 verify --phase=all`. The maintained
`tools/audit/VerifyImportedTd18.jsh` driver also accepts explicit paths through
`TD20_IMPORT_VERIFY_{SOURCE,CERTIFICATES,RESIDUALS,REPORT}` environment variables.

Every second-level residual regenerates both `(1,2,2)` and `(0,3,2)` paths
on both endpoints through budgets ten and eleven. Budget-ten states are
generation obligations: no sorting search or certificate lookup there prunes
their final roots. The checker must replay every final root at budget eleven
and reject missing results. Its completed `inverse-coverage-v2.txt` records
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

## Port validation on 16 November 2026

The clean JDK 21 emitter suite completed 177 tests: 162 passed and 15 CUDA
cases were skipped in the CPU run. The independent checker passed all 95
tests. The separate configured GPU run passed all 15 CUDA cases, including
the 21-symbol regression; the native slot-memory preflight test passed.
The native library was built with CUDA 12.4, GCC 13 and an explicit SM 89
override for the RTX 4070 Laptop. The generic source default remains SM 120.

The expanded source comparison covers **181 shared files** against TD18
commit `22042d8d2274ce05ce1c7868cedab66937eb3ebe`: 113 production/build/native/
resource files, 60 Java test files, seven maintained tools and `.gitignore`.
The reviewed adaptations are frozen in
[td18-domain-adapters.patch](../tools/audit/td18-domain-adapters.patch) and
[td18-test-tool-adapters.patch](../tools/audit/td18-test-tool-adapters.patch).
Repeat the comparison and its independent regression fixtures with:

```bash
bash -ic 'python3 tools/audit/check_td18_machinery.py'
bash -ic 'python3 tools/audit/test_td18_machinery.py'
bash -ic 'python3 tools/audit/test_eriksson_root_publication.py'
```

The audit accounts for all **39 commits** after the corresponding TD18 baseline
and verifies that all **51 retired shared files** are absent, including transient
tools added and removed within that history. It checks exact content and
executable modes and rejects omissions, extra or ignored source files, stale
reference revisions and missing, repeated or reordered ledger rows. All 15
isolated audit regression tests pass. Every upstream Java test has a counterpart.
The root-publication fixture passes 131 isolated checks and the independent
checker publication guard. It exercises all 83 rows and aggregate totals,
missing or malformed reports, partition order, count overflow, malformed table
sections, duplicate or missing markers, atomic replacement and preserved
conditional proof prose. Its synthetic counts never enter the actual paper.

All 83 emitter root-count rows are published: U3 = 0, O3 = 8 and C3 = 2,
leaving six direct obligations. The standalone independent census completed the
first 24 matching root reports before an intentional stop; 59 remain to check.
Completed reports persist, and unfinished partition 25 must regenerate. Both
endpoint census runs agree on all 143 rows. The required CPU suites reran
successfully during census completion: 162 emitter tests passed, 15 CUDA tests
were skipped, and 95 checker tests passed. Census logs and metadata are in
`logs/td20-root-census-completion-20261116/`; the separate versioned root
reports are in `data/eriksson-root-counts-v1/` and
`data/eriksson-root-counts-checker-v1/`. See
[the execution record](REFERENCE_SIMULATION.md) for the actual count-run scope
and timings. Full emission, sorting, inverse coverage and independent proof
checking remain to be run. TD(16) = 9 and TD(18) = 10 are established results
using the same methodology; TD(20) = 11 remains unproved.
Untracked initial validation logs are in `logs/td18-port-20261116/`; the
follow-up review, CPU, CUDA and audit logs are in
`logs/td18-port-review-20261116/`.
