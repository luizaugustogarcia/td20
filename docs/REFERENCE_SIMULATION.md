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

| Scope | Active requirement | Execution status |
| --- | --- | --- |
| Root inventory | 83 partitions on 21 symbols; independently checked U3/O3/C3 | Pending full counts |
| Endpoint inventory | 143 annotations on 16 symbols; budget 8 | Independently counted: 14,098,353,328 classes; 11,523,939,269 outside elementary closures |
| Direct roots | Budget 11 | Pending |
| First inverse wave | 18 symbols; budget 9; both endpoints | Pending |
| Second inverse wave | 20 or 21 symbols; budget 10; both branches/endpoints | Pending |
| Final roots | 21 symbols; budget 11; no unresolved class | Pending |
| Independent verification | Fresh work; every obligation regenerated and replayed | Pending |

Transfer only a closed certificate database for independent verification. Build
the checker independently and start with fresh work; no emitter scratch inventory
or missing obstruction marker can replace regeneration. See
[VALIDATION.md](VALIDATION.md) for exact commands and artifact boundaries.
