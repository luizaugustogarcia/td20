# TD20 computational handoff — 19 November 2026

## Restored reconstruction optimization; production running

On 19 November 2026 the operator requested recovery of the reverted final
inverse-family optimization, verification, commit and push. The implementation
was recovered from its recorded successful patches and the original
24,554,710-source benchmark. It filters proved support families before gap
expansion and replays remaining parents before canonicalization. The emitter
and independent checker generate the same elementary residual cover. Both
fresh FULL_SEARCH runs and residual resumes now use the filtered final wave.
The later automatic nine-move CPU-search draft was not restored.

Production resumed at 05:31:59 UTC on 19 November 2026, launcher PID 2472982,
from the retained completed budget-ten source, in fresh work at
`local-runtime/work/emitter-20261119T053156Z-2472982`. Its log is
`local-runtime/logs/emitter-20261119T053156Z-2472982.log`; persistent terminal
session 32432. It uses all 22 allocated CPUs, a 16 GiB Java heap, scan batches
of 512 source classes and generated-key batches of 4,096. WAL remains disabled.
The log confirms `rootInventory=ELEMENTARY_RESIDUALS` against the exact
4,010,256,622-source denominator. Early rates are not a full-run estimate.

The preceding optimized run ended at 04:36 UTC,
after expanding 307,872,455 of 4,010,256,622 sources. Its interrupted output
was removed at the operator's request along with old reconstruction logs and
superseded experimental work. Runtime settings, benchmark evidence, the
certificate database and the completed budget-ten source remain. Historical
log paths below describe records removed by that cleanup.

The retained completed source is
`local-runtime/work/emitter-20261118T024413Z-706373/after-one`; its exact
count remains 4,010,256,622. The original 970,331-class after-two residual
source is `fleet/production-td20-20261117/td18-after-two-reuse-v1/residuals`.
The measured speedup is final inverse-family filtering, not a reduction of
that after-two count. Preserve project-root `certificates/`; no pilot
certificate database was imported. See
[RECONSTRUCTION_OPTIMIZATION.md](RECONSTRUCTION_OPTIMIZATION.md) for the
derivation, benchmark scope and remaining proof obligations.

Restoration validation: both required CPU suites passed (emitter 301 tests,
16 configured CUDA skips; checker 110 tests), with zero failures or errors.
The cleanup suite passed 12 tests, production CUDA smoke passed two tests,
and the authoritative paper compiled. No new performance benchmark was run.

The following records describe historical launches and inventories.

Local reconstruction restarted at 03:17:48 UTC on 19 November 2026 as PID
2109221 (persistent terminal session 48344). Its work directory is
`local-runtime/work/emitter-20261119T031748Z-2109221`. Local runtime work, logs,
settings and temporary files are under project-root `local-runtime/`, outside
both `fleet/` and Maven `target/` directories. The local profile uses the RTX
4070 Laptop GPU and automatically detected CPU allocation, appending results
to project-root `certificates/`. The active log is
`local-runtime/logs/emitter-latest.log`. The remote fleet and independent
full-proof waiter remain stopped.

The retained completed budget-ten `after-one` database and counted version-two
`AFTER_ONE` checkpoint are at
`/home/luiz/Projects/others/td20/local-runtime/work/emitter-20261118T024413Z-706373`.
The operator chose to retain the two physical waves because disk space is
available and global intermediate deduplication avoids repeated final expansion.
The checkpoint
recorded `afterOne=-1` at restart; the exact intermediate cardinality was not
recorded before the earlier final expansion. A key-only count of this completed
source began with a serial scan at 03:02:09 UTC; that process was retired
for the parallel implementation. The new count started at 03:17:51 UTC with
22 workers and 88 disjoint snapshot ranges, initially counting around
35--39 million keys per second. All 88 ranges completed at 03:19:52 UTC:
the exact inventory is **4,010,256,622** unique logical keys. The source
checkpoint now records `afterOne=4010256622`, and final expansion began
automatically against this fixed denominator. Earlier partial roots are not
a restart boundary, but their valid deterministically keyed certificates remain
reusable. Root settlement is batched certificate lookup, then CPU prefixes, then GPU
fallback only for CPU misses.

The primitive emitter migration was committed and pushed as `387910b`. Its
later local final expansion was stopped during the requested counting and
progress update. Do not resume from its unfinished final-root output. The new
launch resumes the retained completed budget-ten source in fresh work. The
maintained launchers remove their recorded abandoned interrupted work and
temporary files before restarting, while preserving certificate databases,
the selected completed resume source, and other completed-wave checkpoints.

The requested counting update counts distinct persisted budget-ten keys with
`countKeys()` after writer drain and saves the exact total in the `AFTER_ONE`
checkpoint before final inverse generation. This is a key-only merged RocksDB
scan with independent iterators on disjoint half-open snapshot ranges,
without decoding, validation, prefetch, or further Java
deduplication. Legacy uncounted checkpoints are counted and atomically upgraded
before final generation. Final expansion then reports completed expanded
source classes as `processed=X/N (P%)`, where `N` is the exact counted
budget-ten source total and `P=100 X/N` for a nonempty source, together with
remaining source classes and a rate-based ETA. Generated, submitted, consumed and
SST-ingested output key occurrences accumulate separately and can include
duplicates; only the mandatory root scan establishes the exact final class
count. Prefetch reports submitted-so-far and pending work without a misleading
completion percentage. The parallel count completed in about 121 seconds,
averaging approximately 33 million logical keys per second, with observed
production JVM CPU utilization around 1,888 percent (about 19 cores).
Final expansion logs its completed source classes and accumulated output
occurrences against the counted inventory. Both required Maven CPU suites passed
before the earlier serial launch: emitter 289 tests with 16 configured CUDA
skips; checker 105 tests. Both had zero failures and errors. The operator
requested production verification of the parallel update without additional
tests. The parallel code compiled successfully and completed its production
count; the counted checkpoint and live final-expansion logs confirm integration.

The physical emission union was consolidated at project-root certificates/
before local reconstruction. That consolidation used exactly the ten
accepted inputs in RUN/consolidation/complete-v1.txt (all 1,469 emission tasks),
including the five recovered vast-06 inputs. The incomplete crashed-server DB
was excluded. fleet/MergeEmissionCertificates.jsh ran the existing opaque-KV
CertificateDatabaseConsolidator with the coordinator profile and 1 GiB SST
target. Its historical progress is RUN/local-certificate-consolidation/emission.log;
complete-v1.txt was published after final physical integrity validation.
No witnesses were replayed; replay belongs to the independent checker. These
source paths record the historical consolidation inputs; their downloaded
copies are not a reconstruction restart requirement after consolidation.
Preserve the project-root certificate database. The emission union alone is not
the completed proof certificate; reconstruction results and full independent
checking remain required.

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
The 2,036 original endpoint markers remain informational. The historical fleet
configuration selected this residual input as 3,791 batches of at most 256
states, with 91 states in the last batch. Those remote batches were not
started; the last local reconstruction resumed the completed global
budget-ten set. Its exact source count is 4,010,256,622, and the final unique-root
total remains unknown until the mandatory root scan.

Work belongs to /home/luiz/Projects/others/td20. TD18 is closed and restored;
its archived attempts are not TD20 inputs. TD(16) = 9 and TD(18) = 10 are
completed premises. TD(20) = 11 remains conditional on every checked criterion
in docs/RESEARCH_PLAN.md.

Run: fleet/production-td20-20261117.
UUID: b360c418-25af-4100-a62d-40b4b226817d.
The complete fleet tree was moved out of target at the operator's request.
Keep computational data/configuration outside Maven build directories.
All durable fleet artifacts are ignored under project-root fleet/.
Earlier attempts were deleted. The completed emission was consolidated into
project-root `certificates/`, which the local reconstruction preserves; never
reset or mix in older output.

All 1,469 emission computations completed. Receipts and sealed residual sets
were downloaded and all 143 annotation inventories validated. The residual
union has 2,036 classes; the first inverse wave has 5,032,950 after-two states.
The archived full-search input has 19,660 reconstruction batches of at most
256 states (last batch 246). These cannot substitute for the residual mode.
Keep 83 root partitions, 143 full endpoint annotations, budgets 8/9/10/11,
both endpoints, and both (1,2,2) and (0,3,2) inverse paths.

## Historical fleet

The following fleet paths and resource allocations describe completed or
stopped attempts. Downloaded source databases are not required for the current
local restart: it uses project-root `certificates/` and the completed budget-ten
source recorded above. Reinspect resources before any new fleet operation.

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

## Historical lost emission recovery

All five surviving emission databases finished downloading at 19:18:52 Lisbon
and passed local file/schema/count/checksum validation by 19:19:34. They were
published under RUN/downloads/vast-02, vast-03, vast-04, vast-05 and vast-08. Their
completion marker is RUN/consolidation/survivors-complete-v1.txt.
vast-06 saved 16/26 SSTs locally; ten SSTs (about 9.6 GiB) are missing. Its
incomplete database was excluded. Original receipts, endpoint residuals,
the 2,036-class union and the prepared 5,032,950-state inverse wave were retained
through recovery.

The operator authorized regenerating the lost data with all five survivors
and cleaning their remote run files before launch. Cleanup completed with no
remote Java workers active and their local accepted backups checked. Remote
source clones/native caches were retained; other old remote run files were
deleted. The cleanup record is RUN/emission-recovery-vast-06-v1/cleanup-v1.txt.
Recovery used fleet/servers-survivors.tsv and each server's individual profile.

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
were preserved through consolidation. The combined scope covered all 1,469
original emission tasks exactly once before
RUN/consolidation/complete-v1.txt was published. Inverse reconstruction and the
independent proof-verification waiter were stopped during recovery. The
consolidated database now belongs at project-root certificates/.

## Historical streaming reconstruction upgrade

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

## Historical fleet completion and separate checking

The earlier remote reconstruction attempt stopped pending a replacement fleet,
with zero remote reconstruction batches completed. Its coordinator, remote
workers, temporary streaming bridge and independent verification waiter were
stopped. The completed fleet emission was later consolidated locally. The
operator subsequently selected local reconstruction;
its current running state, retained source and runtime location are recorded
above. Independent final proof checking still requires the complete certificate.

The old 24-worker per-task download was aborted. All RUN/downloads contents and
partial transfer archives were deleted, freeing 52,030,878,767 bytes. Old successful
downloads had removed their transfer intents, so recovery restored the complete
1,469-task scope from closed remote receipts and matched it against local
validated results.

The authorized replacement is tools/td20 fleet-consolidate, with --run and --servers
pointing to this run and fleet/servers.tsv. It re-inspects all sources, uses
their individual profiles and automatic NUMA flags, and performs physical KV
consolidation concurrently on all six servers. Values remain opaque; no witness
replay occurs. Final native compaction targets 1 GiB ZSTD SSTs. Original closed
databases remain remote. The separate consolidation source snapshot is under
RUN/consolidation; the original computational source.tar/source.bundle was
preserved through consolidation.

After consolidation, 24 independent SSH workers download SSTs in round-robin
server order. SSH recompression is disabled for these already compressed tables.
Files are staged separately per server, then accepted after exact file sizes,
task scope, schema, KV counts and RocksDB checksums pass. Publication is
RUN/downloads/vast-XX/certificates, with RUN/consolidation/complete-v1.txt requiring
every emission task. The accepted sources were later consolidated into the
local certificate database, so current reconstruction does not require these
download directories.
An earlier interrupted local certificates/ database (41 GiB) was never a fleet
input and was deleted at the operator's request before later consolidation.
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
