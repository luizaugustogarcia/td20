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

Load coordinator.jvm into TD20_JSHELL_OPTIONS and set TD20_FLEET_LAUNCH_ARGS to
target/fleet/launch.args. Launch tools/fleet/StartFleet.jsh through JShell, retaining
the plan and UUID. Start the independent waiter with target/fleet/StartVerification.jsh.
It waits for the complete merge, builds an isolated checker from pinned source
and verifies the full scope with fresh checker work.

Certificates remain remote during emission/reconstruction. Only metadata and
endpoint residuals were transferred for preparation. After reconstruction closes,
the bounded background transfer pool downloads every certificate to
RUN/downloads/TASK/ and the verified streaming merge publishes RUN/certificates.
Full proof verification remains separate and pending. Downstream inverse totals
and a reliable complete-run ETA remain unknown.

Follow the concise ten-second RUN/emission.log with bash -ic and tail -F.
RUN/status.txt is atomically replaced and shows preparation, active servers and
retries. Raw task/setup logs are separate. Confirm every server is doing useful
work after restart; endless monitoring and benchmarks are not requested.

All shell commands load ~/.bashrc with bash -ic, locally and remotely. Plain SSH,
git directly (never gh), JShell for Java/scripting, TMUX_STARTED=1 and isolated
binary stdout remain required. Disable RocksDB WAL and flush closed boundaries.
All generated artifacts, settings and logs remain untracked.
