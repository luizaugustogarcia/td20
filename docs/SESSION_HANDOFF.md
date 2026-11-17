# TD20 distributed simulation handoff — 17 November 2026

## Active production

Work belongs to /home/luiz/Projects/others/td20. TD18 is closed and restored;
its archived attempts are not TD20 inputs. TD(16) = 9 and TD(18) = 10 are
completed premises. TD(20) = 11 remains conditional on every checked criterion
in docs/RESEARCH_PLAN.md.

Run: target/fleet/production-td20-20261117.
UUID: b360c418-25af-4100-a62d-40b4b226817d.
Earlier attempts were deleted. Preserve this run's completed emission during
the authorized reconstruction restart; never reset or mix in older output.

All 1,469 emission computations completed. Receipts and sealed residual sets
were downloaded and all 143 annotation inventories validated. The residual
union has 2,036 classes; the first inverse wave has 5,032,950 after-two states.
There are 19,660 reconstruction batches of at most 256 states (last batch 246).
Keep 83 root partitions, 143 full endpoint annotations, budgets 8/9/10/11,
both endpoints, and both (1,2,2) and (0,3,2) inverse paths.

## Fleet

Connections: target/fleet/servers.tsv. Individual profiles: target/fleet/profiles/.
All six active servers have 16 certificate writers.

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
Verify identical Git revisions across servers, without source-file hashes.

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
pointing to this run and target/fleet/servers.tsv. It re-inspects all sources, uses
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
