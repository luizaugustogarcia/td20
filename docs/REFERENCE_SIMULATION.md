# TD20 execution record

No completed full-scope TD20 Eriksson-route emission or independent verification
is recorded here. TD18's completed reproduction and timings belong to TD18 and
cannot establish TD20 coverage. The matching TD20 upper bound remains pending
until every criterion in [RESEARCH_PLAN.md](RESEARCH_PLAN.md) has a checked artifact.

The maintained local launchers create per-run settings and logs under
`target/local-runtime/logs/`, scratch work under `target/local-runtime/work/`,
and use `certificates/` by default. They detect CPU affinity, quota and memory
limits, record the source revision and working-tree status, execution-VM options,
class/native hashes, start/finish timestamps, phase and exit status. These are
execution provenance, not mandatory proof inputs. Generated logs, settings,
databases and work sets remain untracked.

For a completed designated run, record its actual CPU/GPU hardware, allocated
workers, JVM and native toolchains, CUDA architecture, resource limits, source
revision, database and report locations, UTC timestamps with timezone, measured
phase durations and exact completed scope. Keep emission and independent
verification records distinct. Do not infer success from JShell termination:
require the full-scope application success marker and exit zero.
Record the emitter and checker source revisions separately and audit any source
changes between them. Verification uses a closed read-only certificate database
and independently built checker with fresh work, all phases and no ranges or
resume. Record exact regenerated counts, unresolved classes, phase durations,
the full-scope success message and process exit status. Separately record the
test executions and published versioned census reports; a successful replay
run does not supply an unexecuted test or an unpublished report.

| Scope | Active requirement | Execution status |
| --- | --- | --- |
| Root inventory | 83 partitions on 21 symbols; independently checked U3/O3/C3 | All emitter rows published: U3 = 0, O3 = 8, C3 = 2, direct = 6; first 24 independent rows agree, 59 remain |
| Endpoint inventory | 143 annotations on 16 symbols; budget 8 | Independently counted: 14,098,353,328 classes; 11,523,939,269 outside elementary closures |
| Direct roots | Budget 11 | Pending |
| First inverse wave | 18 symbols; budget 9; both endpoints | Pending |
| Second inverse wave | 20 or 21 symbols; budget 10; both branches/endpoints | Pending |
| Final roots | 21 symbols; budget 11; no unresolved class | Pending |
| Independent verification | Fresh work; every obligation regenerated and replayed | Pending |

## Root census on 16 November 2026

The emitter census completed all 83 partitions on 21 symbols at budget 11,
publishing U3 = 0, O3 = 8 and C3 = 2, with six direct obligations. It ran
through JShell on JDK 21.0.4 with 22 automatically detected JVM-visible workers.
The resumed phase reused 24 compatible complete emitter reports and ran from
12:07:00.282170 to 12:34:03.463484 UTC, taking 27 min 03.181 s, with exit zero
and the complete 83-partition application marker. The corrected atomic
publisher then completed with exit zero. Phase timestamps come from the log
files' recorded creation and last-modified times.

The independently built checker census ran from 12:34:03.759084 to
13:57:30.496622 UTC, taking 1 h 23 min 26.738 s and reusing three compatible
complete reports. It completed the first 24 reports, all agreeing with the
paper in every U3/O3/C3 field. The standalone census was intentionally stopped
with SIGTERM, exit 143, after validation was scoped to complete small-domain
tests and reviewed source equivalence. Fifty-nine independent rows remain;
unfinished partition 25 must regenerate. The completed reports persist, and
the planned fresh full proof checker must regenerate the complete scope.
The route is `td20-eriksson-three-v1`, and both modules persist
`td20-eriksson-root-inventory-v1` reports in their separate
`data/eriksson-root-counts-v1/` and `data/eriksson-root-counts-checker-v1/`
directories.

Untracked logs and metadata are under
`logs/td20-root-census-completion-20261116/`: `emitter.log`, `checker.log`,
`publication.log`, `settings.json`, `publication-settings.json`,
`phase-timing.json`, `source-record.json` and worker-utilization logs. The
source record identifies revision `84cd4b5eead5adbeea11e5133b4e277af5f8005d`
and the working-tree changes used for this census. These counts discharge no
sorting, inverse-coverage or full proof-verification obligation.

Both required CPU suites passed their exhaustive retained-root-class tests
for every domain of three through eight symbols. The tests enumerate every
valid fixed-point-free input and compare the generated class set with an
independent oracle moving the actual current and target cycles on arbitrary
supports; duplicate output classes fail. Separate passed tests compare serial
and parallel counts and cover the 21-symbol reverse exception. These unit
checks and the source-equivalence audit validate the port within their executed
scopes. Full 21-symbol independent counting and witness replay remain pending.

Transfer only a closed certificate database for independent verification. Build
the checker independently and start with fresh work; no emitter scratch inventory
or missing obstruction marker can replace regeneration. See
[VALIDATION.md](VALIDATION.md) for exact commands and artifact boundaries.

TD(16) = 9 and TD(18) = 10 are established results using the same computational
methodology. The latter has a
[completed sister-project reference record](https://github.com/luizaugustogarcia/td18/blob/22042d8d2274ce05ce1c7868cedab66937eb3ebe/docs/REFERENCE_SIMULATION.md).
Its separate-machine checker completed on 16 November 2026 at 10:11:21
Lisbon time after 1 h 45 min 43 s, with exit zero and all 1,349,910,890
reconstructed TD18 root classes verified. Both execution records are retained
in TD18. That verification skipped tests and did not republish standalone census
reports; the evidence checklist keeps those requirements distinct.
