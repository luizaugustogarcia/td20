# TD20 distributed simulation handoff — 17 November 2026

## Correct project and current state

This distributed simulation belongs to `/home/luiz/Projects/others/td20` in WSL
`Ubuntu-24.04`. TD(18) = 10 is a completed premise. The TD18 project was restored
to commit `701dce95bd225e3ea5d0bbcb937ddd826f18b55a` in both WSL checkouts, and
its remote main branch was force-updated to that exact revision.

The mistaken TD18 coordinator was stopped. The complete `/workspace/td18-fleet`
tree was deleted on all six remote servers, and the old PersonalUbuntu
`/home/luiz/Projects/td18/target/fleet` data were deleted. The local aborted run,
its certificates/logs/checkpoints and all migration backups were also deleted
at the user’s request. Nothing from that attempt is a TD20 production input.

The first TD20 production attempt was subsequently stopped at the user's
request, and its complete local production directory and all six remote
`/workspace/td20-fleet` trees were deleted on 17 November 2026. The user requested
a fresh restart after deferring certificate downloads until the final merge.
The second attempt was also stopped and its complete local production directory
and all six remote TD20 run trees were deleted on 17 November 2026. The user
paused before the replacement launch, then removed `88.90.102.66` (`vast-01`)
from the active fleet. The user then added `69.176.92.147:39547` (`vast-07`),
restoring the active fleet to six servers. Its JDK 21, Maven and CMake installation,
pinned-source clone, Java compilation and hardware inspection completed. It has
185 JVM-visible CPUs, 211 GiB allocated RAM, two sockets, two NUMA nodes and an
RTX 5080. Its individual profile explicitly enables `-R-XX:+UseNUMA`.
The restart is still paused; no earlier production output is an input. The user
authorized committing/pushing the implementation and synchronizing all six
servers to the same Git revision, without source-file hashes.

## Implemented and compiled

The moved implementation includes plain-SSH preparation, pinned local-source
Git bundles when no token is configured, JShell orchestration, live hardware
inspection, independent per-server resource profiles, automatic remote-JVM
NUMA flags, a dynamic prefix-task queue, counted reconstruction batches, staged
deferred certificate downloads, strict versioned receipts, disk-backed deduplication, strongest-witness
merging and concise consolidated progress with atomically replaced status.txt.

TD20 domains and budgets are explicit: 83 root partitions on 21 symbols with
budget 11; 143 endpoint annotations on 16 symbols with budget 8; after-two budget
9 and after-one budget 10. Both inverse paths and both endpoints remain required.
The TD20 certificate schema is preserved, inferred certificate domains admit 21
symbols, and the merger enforces the TD20 root budget 11. The independent checker
was not modified or made dependent on emitter artifacts.

`bash -ic ./tools/td20 build -o` completed successfully after the port. The later
deferred-download extension passed both required Maven CPU suites; the emitter
reported 206 tests with zero failures/errors and 15 skipped tests, and the checker
reported 95 tests with zero failures/errors and no skips. This is implementation
validation, separate from the requested production launch without startup tests
or replay. Full proof verification remains pending. Existing port-audit specifications describe the earlier frozen
TD18 comparison; they do not yet certify the new distributed extension. Update
that comparison record when auditing the extension, without claiming it passed.

## Ready local configuration

- Connections: `target/fleet/servers.tsv`.
- Active individually selected profiles: `target/fleet/profiles/vast-02.jvm` to `vast-07.jvm`.
- Coordinator profile: `target/fleet/coordinator.jvm` (this instance: 22 CPUs, 16 GiB heap).
- Launch arguments: `target/fleet/launch.args`.
- Fresh directory: `target/fleet/production-td20-20261117`, absent after cleanup and to be recreated when the user resumes.
- Initial granularity: target 16 endpoint prefixes/type; at most 256 after-two states/batch.

| Node | SSH destination | GPU | CPUs | Heap GiB | Slots | MiB/slot |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| vast-02 | root@208.102.196.176:40497 | RTX 5080 | 31 | 24 | 16 | 768 |
| vast-03 | root@113.150.232.222:44197 | RTX 5070 Ti | 31 | 48 | 12 | 1024 |
| vast-04 | root@50.5.143.218:52138 | RTX 5080 | 31 | 24 | 16 | 768 |
| vast-05 | root@192.234.50.153:1334 | RTX 4090 | 31 | 40 | 24 | 768 |
| vast-06 | root@192.234.50.233:3166 | RTX 5090 | 47 | 40 | 32 | 896 |
| vast-07 | root@69.176.92.147:39547 | RTX 5080 | 185 | 128 | 16 | 768 |

These are explicit starting resource overrides for the inspected allocations,
not measured TD20 throughput presets. Re-inspect each server before launch
and tune it individually from actual utilization. Remote work is now scoped to
`/workspace/td20-fleet`. The 4090 uses SM 89; the other servers use SM 120.

## Next actions

1. Keep the restart paused until the user resumes. Then read AGENTS.md and
   docs/DISTRIBUTED.md. Confirm no TD20 coordinator is active
   and the proposed run directory is fresh. Use this checkout’s pinned source.
2. Respect the user’s instruction: **skip startup replay/tests and start production
   directly**. Mandatory production inventory checks and hardware inspection remain.
3. Load target/fleet/coordinator.jvm into TD20_JSHELL_OPTIONS and set
   TD20_FLEET_LAUNCH_ARGS to target/fleet/launch.args. Launch tools/fleet/StartFleet.jsh
   through JShell. No token recovery is needed; the local Git bundle supplies source.
4. Confirm all six active servers are working, zero reused outputs, and concise
   emission.log/status.txt updates. Monitor failures, useful CPU/GPU utilization,
   disk space, transfer overhead and unequal task sizes. Tune each server separately.
   Servers claim another computation after persisting a closed result's transfer
   intent. Certificate databases stay remote through emission and reconstruction;
   only receipts and residual sets are transferred before local inverse
   preparation. Final certificates download through a bounded queue using
   dedicated SSH connections. The coordinator derives transfer concurrency from
   the fleet size and enforces every required validation boundary.
5. Let emission, downloads, reconstruction and merging finish. Independent
   full-scope checking against the merged TD20 database remains a separate run.

All shell commands, including SSH remote commands, must load ~/.bashrc with
bash -ic. Use plain ssh and git, never gh. Use JShell for scripting, never Python.
Preserve TMUX_STARTED=1 and stdout isolation for binary transfers. Every RocksDB
write disables WAL and completed boundaries flush. Generated data stay untracked.
The authoritative mathematical route is docs/td20-reduction-count.tex. Keep every
criterion in docs/RESEARCH_PLAN.md, and never label TD(20) = 11 proved prematurely.
