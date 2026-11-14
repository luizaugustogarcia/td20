# Checked active route on 2026-11-13

This is a completed route migration and CPU validation, not a completed
TD(20) proof. Sorting results and final inverse coverage remain pending.

The source specification is [td20-reduction-count.tex](td20-reduction-count.tex).
Java computations ran through JShell on JDK 21, using all 16 JVM-visible CPU
cores. Counts use runtime worker detection, without a machine-specific default.
The checker builds independently and has no emitter artifact dependency.

| Check | Result |
| --- | --- |
| `mvn -pl emitter -am test` | Passed |
| `mvn -pl checker -am test` | Passed |
| Full-cycle small-domain forward oracle | Complete class sets through 8 symbols agree |
| Independent inverse oracle | All gains 0–3, both endpoints, through 7 symbols agree |
| Async root persistence and three-wave resume regressions | Passed |
| Explicit certificate budgets in both modules | Passed; longer valid results are misses at smaller budgets |
| Emitter and checker endpoint census | All 143 rows agree; 14,098,353,328 classes, 11,523,939,269 requiring discharge |
| JShell driver exit checks | Passed for success, runtime errors, and compilation failure |
| Source paper and reading PDF | Built successfully with resolved references |
| Shared TD(18) runtime machinery audit | Passed, 94 source files; common CPU deadline preserved |
| Emitter root census | All 83 partitions complete: 0 unoriented, 8 oriented, 2 cleared, 6 direct; independent full audit complete and matching |
| Full TD(20) proof | Pending |

The endpoint domain has 16 symbols and budget 8; the direct root domain
has 21 symbols and budget 11. Both `(1,2,2)` and `(0,3,2)` inverse
patterns participate on both endpoints. Gain-zero first moves are included.
No stricter endpoint 3-norm cutoff or predicted sorting-failure list is assumed.

TD(18) = 10 is the explicit completed premise supplied for this project only.
The TD18 repository is not thereby asserted to have completed its own proof.
`td20.cpuPrefixMillis` is one common CPU-prefix deadline for all phases.

## Reproduce and inspect artifacts

```bash
tools/td20 build
tools/td20 test
tools/td20 inventory
tools/td20 count-roots
tools/td20 audit-roots
tools/td20 count-endpoints
tools/td20 audit-endpoints
```

The complete root commands validate and resume versioned per-partition reports.
`count-roots` publishes active paper rows only after the complete inventory is
present. A partial source census does not authorize emission or a proof claim.

Generated artifacts stay untracked:

- `data/eriksson-root-counts-v1/` and `data/eriksson-root-counts-checker-v1/`:
  versioned emitter/independent counts, explicit route, domain and budget.
- `data/eriksson-endpoint-counts-v1/` and
  `data/eriksson-endpoint-counts-checker-v1/`: full annotated endpoint census.
- `certificates/`: intrinsic witnesses, replayed at the requested budget.
- `logs/eriksson-route-v1/`: census, tests, certificate and verification logs.

The checker regenerates obligations and replays the database; count summaries,
emitter checkpoints, checksums and sidecar manifests are not proof inputs.
Missing data are errors, and missing obstruction markers cannot omit work.
Partial runs do not discharge the complete route.
CUDA-dependent tests were skipped in this CPU migration validation.

## Cleanup validation on 2026-11-13

The superseded selectors, inventories, inverse waves, launch entries, census
publishers, prototype comparisons, historical appendix and counting reports
have been removed. The emitter now owns a single active root generator; shared
move operations and the checker's budgeted search state have independent,
route-neutral classes. Existing intrinsic certificate wire formats and checked
witnesses remain replayable.

Both required CPU suites passed from cleaned module outputs. The inventory
checks cover missing, duplicate, reordered, malformed and pending rows. Current
endpoint budgets round-trip through the checker's disk set; no former phase
minimum is inferred from a state domain. Empty-database tests reject missing
root certificates and regenerate endpoint obligations without stored markers.
The checker remains independent of the emitter artifact.

The emitter and independent root censuses completed through JShell on all
available CPU cores. All 83 partition reports agree. Completed active census
reports are preserved; sorting and inverse coverage remain separate requirements.

The resumed emitter census completed all 83 partitions during cleanup: 6
direct classes remain. Full independent regeneration now matches every row;
sorting witnesses remain separate obligations.

## Independent census checked on 2026-11-14

All 83 emitter and checker reports match individually, including their route,
domain, budget, partition and retained/cleared counts. Every active paper row
matches the reports: 0 unoriented, 8 oriented, 2 support-cleared, 6 direct.
The census used 16 workers through JShell and completed successfully.
These are class counts; TD(20) sorting certificates and complete endpoint/inverse
coverage remain pending.

## Endpoint tables checked on 2026-11-14

The complete endpoint table is embedded in the source paper and reading PDF,
including zero-count annotations, move budgets, 3-norms, total class counts and
non-elementary discharge obligations. Every table row matches both versioned
census report sets. Root inventory totals are printed as well. PDF text and
layout were checked after rebuilding; both required CPU suites passed.
Reproduce the table using `tools/td20 endpoint-table` after the two endpoint
censuses. The checked tables are summaries; proof verification still regenerates
obligations and replays certificates independently.
