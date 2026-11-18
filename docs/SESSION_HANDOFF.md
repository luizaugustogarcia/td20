# TD20 computational handoff — 18 November 2026

## Active production

The operator chose local residual reconstruction on 18 November 2026.
RUN/local-residual-reconstruction-v1 uses the RTX 4070 Laptop GPU and the
automatically detected local CPU allocation, appending results to project-root
certificates/. Its logs/emitter-latest.log and console.log expose progress.
The remote fleet and independent full-proof waiter remain stopped.

The first local run completed and deduplicated the 44 GiB budget-ten
`after-one` database, then began the final inverse expansion. That process was
stopped. The operator chose to retain the two physical waves because disk space
is available and global intermediate deduplication avoids repeated final
expansion. Reconstruction resumed from the completed version-three `AFTER_ONE`
checkpoint at `work/emitter-20261118T024413Z-706373` on 18 November 2026. Its
`afterOne=-1` count is established during this mandatory final expansion. The
new work directory is `work/emitter-20261118T143031Z-1431181`. Earlier partial
roots are not a resume boundary, but their valid deterministically keyed
certificates remain reusable. Root settlement is batched certificate lookup,
then CPU prefixes, then GPU fallback only for CPU misses.

The operator now requests a single physical emission database at project-root
certificates/, before reconstruction. Local consolidation uses exactly the ten
accepted inputs in RUN/consolidation/complete-v1.txt (all 1,469 emission tasks),
including the five recovered vast-06 inputs. The incomplete crashed-server DB
is excluded. fleet/MergeEmissionCertificates.jsh runs the existing opaque-KV
CertificateDatabaseConsolidator with the coordinator profile and 1 GiB SST
target. Progress is RUN/local-certificate-consolidation/emission.log; its
complete-v1.txt is published only after final physical integrity validation.
No witnesses are replayed; replay belongs to the independent checker. Preserve
all ten source DBs. This emission union is not the completed proof certificate:
the later reconstruction results must still be incorporated.

The physical emission union completed at 21:28:20 UTC on 17 November 2026,
with 11,523,937,239 witnesses and 2,036 informational endpoint markers.
The TD18 import subsequently copied 4,062,619 replayed budget-nine
diameter-prefix certificates directly into this project-root database and
persisted 970,331 18-symbol second-level obstruction markers.
RUN/td18-after-two-reuse-v1/residuals contains the sealed active after-two
state set, its RESIDUAL_EXPANSION checkpoint, and td18-after-two-import-v1.txt.
The independent checker audit of all 5,032,950 source states completed:
4,062,619 replayed hits, 970,331 missing classes, exact residual-set equality,
and every residual marker present. Its report is
RUN/td18-after-two-reuse-v1/independent-import-scope-v1.txt. This checks the
persisted import scope; full endpoint regeneration and TD20 proof checking
remain pending. Import and audit logs are under logs/td18-after-two-reuse-v1/.
The separate marker-inventory-v1.txt audit confirms exactly 2,036
informational 16-symbol markers and 970,331 second-level 18-symbol markers.
The importer published its complete artifacts before its shell wrapper
encountered an error caused by editing the running launcher. Launcher syntax
and both subsequent independent audits passed; the audit processes exited zero.
Final CPU suites passed: emitter 242 tests, zero failures/errors and 15
configured CUDA skips; checker 105 tests, zero failures/errors/skips.
The updated paper also compiled successfully.

Active reconstruction uses only these 970,331 second-level residuals,
retains a canonical disk-deduplicated budget-ten intermediate wave without
budget-nine or budget-ten sorting, and searches the final 21-symbol roots at
budget eleven.
The 2,036 original endpoint markers remain informational. fleet/launch.args
now explicitly selects this residual input. At batch size 256 the active
scope is 3,791 batches, with 91 states in the last batch; their preparation
and execution have not started. Final inverse totals remain unknown.

Work belongs to /home/luiz/Projects/others/td20. TD18 is closed and restored;
its archived attempts are not TD20 inputs. TD(16) = 9 and TD(18) = 10 are
completed premises. TD(20) = 11 remains conditional on every checked criterion
in docs/RESEARCH_PLAN.md.

Run: fleet/production-td20-20261117.
UUID: b360c418-25af-4100-a62d-40b4b226817d.
The complete fleet tree was moved out of target at the operator's request.
Keep computational data/configuration outside Maven build directories.
All durable fleet artifacts are ignored under project-root fleet/.
Earlier attempts were deleted. Preserve this run's completed emission during
the authorized reconstruction restart; never reset or mix in older output.

All 1,469 emission computations completed. Receipts and sealed residual sets
were downloaded and all 143 annotation inventories validated. The residual
union has 2,036 classes; the first inverse wave has 5,032,950 after-two states.
The archived full-search input has 19,660 reconstruction batches of at most
256 states (last batch 246). These cannot substitute for the residual mode.
Keep 83 root partitions, 143 full endpoint annotations, budgets 8/9/10/11,
both endpoints, and both (1,2,2) and (0,3,2) inverse paths.

## Fleet

Connections: fleet/servers.tsv. Individual profiles: fleet/profiles/.
The five surviving active servers have 16 certificate writers. vast-06 crashed
irrecoverably and must not be contacted or included in the recovery fleet.

| Node | SSH destination | GPU | JVM CPUs | Heap GiB | GPU slots | MiB/slot |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| vast-02 | root@208.102.196.176:40497 | RTX 5080 | 31 | 24 | 16 | 768 |
| vast-03 | root@113.150.232.222:44197 | RTX 5070 Ti | 31 | 48 | 12 | 1024 |
| vast-04 | root@50.5.143.218:52138 | RTX 5080 | 31 | 24 | 16 | 768 |
| vast-05 | root@192.234.50.153:1334 | RTX 4090 | 31 | 40 | 24 | 768 |
| vast-06 | root@192.234.50.233:3166 | RTX 5090 | 47 | 40 | 32 | 896 |
| vast-08 | root@113.150.232.222:48295 | RTX 5070 Ti | 31 | 48 | 12 | 1024 |

vast-01 and expensive NUMA vast-07 were removed. vast-08 is initialized with
JDK21, Maven, CMake, cloned source and native compilation. vast-03 and vast-08
share an IP but are distinct containers/ports. Coordinator: 22 JVM CPUs, 16GiB
heap, eight certificate/reconstruction writers. Re-inspect resources before
computing; automatic UseNUMA applies to multiple allocated sockets/NUMA nodes.
Verify identical Git revisions across surviving servers, without source-file hashes.

## Lost emission recovery

All five surviving emission databases finished downloading at 19:18:52 Lisbon
and passed local file/schema/count/checksum validation by 19:19:34. They are
under RUN/downloads/vast-02, vast-03, vast-04, vast-05 and vast-08. Their
completion marker is RUN/consolidation/survivors-complete-v1.txt.
vast-06 saved 16/26 SSTs locally; ten SSTs (about 9.6 GiB) are missing. Its
incomplete database is not an input. All original receipts, endpoint residuals,
the 2,036-class union and the prepared 5,032,950-state inverse wave are retained.

The operator authorized regenerating the lost data with all five survivors
and cleaning their remote run files before launch. Cleanup completed with no
remote Java workers active and their local accepted backups checked. Remote
source clones/native caches were retained; other old remote run files were
deleted. The cleanup record is RUN/emission-recovery-vast-06-v1/cleanup-v1.txt.
Use fleet/servers-survivors.tsv and each server's existing individual profile.

tools/td20 fleet-recover-emission --run=fleet/production-td20-20261117
--servers=fleet/servers-survivors.tsv --lost-server=vast-06 regenerates exactly
the 333 task definitions recorded in RUN/consolidation/vast-06-inputs-v1.txt.
It uses the existing shared dynamic queue, schedules larger endpoint prefixes
first, re-inspects resources, applies automatic NUMA flags, and performs no
startup sorting tests/replay. Its state and ten-second log are under
RUN/emission-recovery-vast-06-v1. Both mandatory local CPU suites passed.

Regenerated receipts and every residual class must match the preserved
originals before adopting certificates. New emission databases are physically
consolidated per server, targeting 1 GiB SSTs, then transferred with bounded
parallel SSH workers. Retries resume at the saved byte offset. Publication uses
RUN/downloads/recovered-vast-06-vast-XX/certificates; the five survivor inputs
remain intact. The combined scope must cover all 1,469 original emission tasks
exactly once before RUN/consolidation/complete-v1.txt is published.
Inverse reconstruction and the independent proof-verification waiter remain
stopped. The final single merged database still belongs at project-root
certificates/ after inverse reconstruction completes.

## Authorized streaming reconstruction upgrade

The operator requested parallel batch preparation, immediate dispatch of sealed
batches, local unit tests, deployment and reconstruction restart. No experiments
or production startup tests/replay. Small batches use one explicitly flushed,
WAL-disabled WriteBatch in the existing state format. Atomic publication contains
the input database, checkpoint and task scope. A bounded queue feeds servers
while later batches are prepared. Persistence workers default to coordinator
CPU count; ready capacity defaults to eight batches per server. Existing slices
are matched against regenerated sorted keys. Reused results still require every
validated publication. Missing batches or producer failure cannot close production.

Stop the old serial coordinator, temporary streaming bridge and all remote
workers before deployment. StopTd20.jsh preserves data; ResetTd20.jsh deletes it
and must not be used for this upgrade. Adopt the existing partial preparation
tree as RUN/reconstruction, preserving sealed batches. Regenerate marked waves
if absent rather than trust an interrupted legacy global wave. Archive and replace
source.tar and source.bundle with the new committed implementation. Existing
remote Git clones fetch/reset to pinned bundle HEAD before source overlay/build.
Generated source-upgrade receipts and logs stay ignored.

Both mandatory Maven CPU suites passed for the initial streaming implementation.
Dispatcher overlap, cancellation and result reuse regressions were then added;
module target logs contain the final test totals. The independent checker is
unchanged. The frozen TD18 comparison has not been updated/rerun for these
explicitly authorized distributed extensions.

## Completion and separate checking

Reconstruction is stopped at the operator's request, pending a replacement fleet.
Zero reconstruction batches completed. The coordinator, remote workers, temporary
streaming bridge and independent verification waiter are stopped. Preserve all
completed emission databases, RUN/results and RUN/reconstruction. Do not restart
reconstruction or its waiter until the operator supplies the replacement fleet.

The old 24-worker per-task download was aborted. All RUN/downloads contents and
partial transfer archives were deleted, freeing 52,030,878,767 bytes. Old successful
downloads had removed their transfer intents, so recover the complete 1,469-task
scope from closed remote receipts and match them against local validated results.

The authorized replacement is tools/td20 fleet-consolidate, with --run and --servers
pointing to this run and fleet/servers.tsv. It re-inspects all sources, uses
their individual profiles and automatic NUMA flags, and performs physical KV
consolidation concurrently on all six servers. Values remain opaque; no witness
replay occurs. Final native compaction targets 1 GiB ZSTD SSTs. Original closed
databases remain remote. The separate consolidation source snapshot is under
RUN/consolidation; preserve the original computational source.tar/source.bundle.

After consolidation, 24 independent SSH workers download SSTs in round-robin
server order. SSH recompression is disabled for these already compressed tables.
Files are staged separately per server, then accepted after exact file sizes,
task scope, schema, KV counts and RocksDB checksums pass. Publication is
RUN/downloads/vast-XX/certificates, with RUN/consolidation/complete-v1.txt requiring
every emission task. Resume can use these six DBs without old emission servers;
the later replaying merge still includes every completed reconstruction result.
The project-root certificates/ was an earlier interrupted local run (41 GiB),
never a fleet input, and was deleted at the operator's request.
The operator selected project-root certificates/ for the final single merged
database after reconstruction. fleet/launch.args includes --database=certificates,
and RUN/merged-database-v1.txt records that destination. The separate verification
waiter is configured for this database and remains stopped.

Consolidation progress is RUN/consolidation/emission.log and status.txt. Raw setup
and per-server logs remain separate. Full proof verification remains separate
and pending, with fresh checker work after the final merged certificate exists.
Downstream inverse totals and a reliable complete-run ETA remain unknown.

Follow the concise ten-second RUN/emission.log with bash -ic and tail -F.
RUN/status.txt is atomically replaced and shows preparation, active servers and
retries. Raw task/setup logs are separate. Confirm every server is doing useful
work after restart; endless monitoring and benchmarks are not requested.

All shell commands load ~/.bashrc with bash -ic, locally and remotely. Plain SSH,
git directly (never gh), JShell for Java/scripting, TMUX_STARTED=1 and isolated
binary stdout remain required. Disable RocksDB WAL and flush closed boundaries.
All generated artifacts, settings and logs remain untracked.
