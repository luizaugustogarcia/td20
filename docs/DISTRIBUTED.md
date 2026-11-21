# Distributed emission over SSH

The TD20 fleet covers 83 root partitions on 21 symbols at budget 11 and all
143 endpoint annotations on 16 symbols at budget 8. Its after-two and after-one
budgets are 9 and 10. TD(16) = 9 and TD(18) = 10 are completed premises; this
distributed computation does not establish TD(20) = 11 until every required
independent verification and evidence criterion succeeds.

The active reconstruction input is the complete sealed set of fixed-point-free
18-symbol classes still lacking a budget-nine result after witness replay. Supply
`--reconstruction-input=RUN/td18-after-two-reuse-v1/residuals`; the coordinator
also detects this conventional location automatically. The 2,036 endpoint
misses remain informational and do not seed production reconstruction.
Residual checkpoints select `RESIDUAL_EXPANSION`: expand every input through
both inverse branches, omit budget-nine and budget-ten searches, and search only
the final 21-symbol roots at budget 11. The checker regenerates the obligations
independently from endpoint generation and the copied TD20 results; neither
the TD18 source database nor persisted obstruction markers select checker work.

`tools/td20 fleet` accepts a list of Vast.ai SSH connections, builds a pinned
snapshot of the current source on each server, schedules independent emitter
jobs, downloads closed RocksDB databases, and merges their certificates locally.
The coordinator and all Java entry points run through JShell. Local and remote
commands load `~/.bashrc` with `bash -ic`; transfers use plain SSH and tar.
Batch shells suppress Vast's automatic tmux attachment with `TMUX_STARTED=1`.
Startup output goes to stderr so shell banners cannot corrupt binary transfers.

On connection the coordinator runs `sudo apt install openjdk-21-jdk maven cmake -y`,
then enters `/workspace`, clones the repository, applies the pinned local source
snapshot, and runs the Maven compilation/classpath preparation before any job.
Cloning uses `--no-checkout`: the complete pinned snapshot supplies the work tree,
including local changes, without mixing in files from a newer remote checkout.
The default repository is `https://github.com/luizaugustogarcia/td20.git`; use
`--repository=HTTPS_GITHUB_URL` to override it. The launcher selects the installed
JDK 21 explicitly after loading the remote environment.

Servers must already have Bash, sudo, apt, git, tar, flock and the CUDA toolchain.
The SSH user needs noninteractive sudo access. Configure noninteractive SSH access
and accept each host key with your usual SSH connection first. The coordinator
does not provision Vast instances or install CUDA. Local preparation and merging need JDK 21, Maven, git and tar, with
enough disk space for the downloaded databases and the merged output.
Production databases, checkpoints, downloads and fleet configuration belong
outside Maven target directories. The examples use an ignored project-root
fleet/ directory; target directories hold disposable build products.

Without `TD20_GITHUB_TOKEN`, the coordinator creates a Git bundle from the local
checkout, transfers it over SSH, and clones that pinned bundle on each server.
The source snapshot then overlays current local changes. This supports a fresh
run from an already available private checkout without recovering credentials
from another process. The bundle and all generated data remain under `target/`.
Existing bundle-backed clones fetch the pinned bundle's HEAD and reset their
Git index to that revision before the snapshot overlay. An ordinary resume
retains its original source archive; an authorized source upgrade archives and
replaces source.tar and source.bundle while all workers are stopped.
Residual reconstruction uses a separate `source-residuals-v1.tar` snapshot and
separate task/transfer identities, so archived full-search tasks cannot resume
under the new mode.

Set `TD20_GITHUB_TOKEN` in the local launch environment to clone from GitHub instead. Authentication
travels over SSH standard input into transient Git configuration, with credential
helpers disabled. The token is absent from process arguments, repository URLs,
persisted plans and Git configuration. Credential text is redacted before either
the per-server log or consolidated log receives it. The coordinator removes the
token from subprocess environments; compilation and workers do not receive it.
Never add a token to the TSV file or a tracked script.

Copy [the example](../tools/fleet/servers.example.tsv) into an ignored directory
and replace its connection details. Each non-comment line has exactly five
TAB-separated fields:

1. A unique server name.
2. A plain connection such as `ssh -p 12345 -i /path/to/key root@host`.
   SSH config aliases, quoted identity paths, `-o`, `-F`, `-J` and `-l` are supported.
3. An absolute remote work directory under `/workspace`.
4. The CUDA architecture number, for example `120` for the RTX 5090.
5. Server-specific JShell JVM options, or `@profiles/server-a.jvm`. Relative
   profile paths are resolved against the TSV file. Profile files accept one
   or more options per line and full-line `#` comments. Embedded whitespace in
   an individual option is rejected.

Each server requires an explicit maximum heap (`-R-Xmx...`), JVM CPU allocation
(`-R-XX:ActiveProcessorCount=...`) and producer count
(`-R-Dtd20.twistedBraceletWorkers=...`). GPU servers also require explicit
`td20.gpuDevices`, `td20.gpuSlots` and `td20.gpuBudgetMB` settings. The fleet
injects no shared hardware preset. [The two example profiles](../tools/fleet/profiles/)
have different CPU, heap, CUDA and queue configurations; they are unmeasured
starting examples, not tuned recommendations for an unknown allocation.

Inspect every server first. Inspection performs the requested installation,
clone and Java compilation, then records the JVM-visible CPU allocation, physical
socket and NUMA topology, RAM, and each GPU's name, capability and free/total VRAM.
It does not start emission or build CUDA. The fifth TSV field may be empty during
inspection. Use the reported allocation to choose that server's profile, and
measure utilization with the actual workload before finalizing it.

Every computing connection repeats inspection before applying its profile. A
CPU allocation beyond the available threads, a heap consuming all RAM, absent
GPU devices, an incompatible CUDA target or queues consuming the available VRAM
are rejected. Leave additional RAM for RocksDB, queues and the OS, and VRAM for
native state/cache overhead. Native CUDA preflight checks the full allocation.
After building CUDA, each server claims production work directly. There is no
automatic sorting replay or test run before production.

For **multiple CPU sockets or multiple NUMA nodes**, the launcher automatically
adds `-R-XX:+UseNUMA`. The `-R-` form passes `-XX:+UseNUMA` to the Java VM that
executes the emitter through JShell. Disabling NUMA on such a server is rejected.
Topology respects CPU and memory affinity. A single socket and single NUMA node
does not receive the flag automatically. Failure to establish topology is an
error rather than an assumption of a single socket.

Tune CPU producers, inverse workers, GPU callers, persistence workers, heap/GC,
queue sizes, lookup batches and CPU-prefix deadlines independently per server.
The runtime clears ambient Java option variables before inspection and worker
launch, so another machine's flags cannot override the chosen profile. Native
compilation uses that server's configured CPU allocation. Architecture `cpu`
skips the native build and suits small jobs whose CPU prefixes close every
obligation; a CPU miss still requires the normal CUDA fallback.

```bash
bash -ic './tools/td20 build'
bash -ic 'mkdir -p fleet'
bash -ic 'cp tools/fleet/servers.example.tsv fleet/servers.tsv'
bash -ic 'cp -R tools/fleet/profiles fleet/'
# Set TD20_GITHUB_TOKEN in the launch environment without saving it in these files.
# Edit the connections, then inspect the actual servers before tuning their profiles:
bash -ic './tools/td20 fleet --servers=fleet/servers.tsv --run=fleet/inspection --inspect-only'
# Edit each profile using its own hardware report and workload measurements.
# Preview the selected settings without SSH:
bash -ic './tools/td20 fleet --servers=fleet/servers.tsv --run=fleet/run-01 --plan-only'
bash -ic './tools/td20 fleet --servers=fleet/servers.tsv --run=fleet/run-01 --database=certificates --endpoint-tasks=512 --reconstruction-batch-size=64'
```

Repeat the command to resume. The persisted plan fixes connections, directories,
CUDA architectures, work scope and batch sizes; `source.tar` pins the source including current
tracked edits and new source files. Source packaging includes only the project
source/documentation paths, excludes ignored files and the run directory, and
does not include `.git`, local databases, or SSH identities. Each server has one
active computation at a time and takes the next queued job when free. A remote lock
prevents overlapping attempts after a connection interruption. A server claims
the next queued task as soon as computation completes and its transfer intent is
persisted. Certificate databases stay remote throughout emission and
reconstruction. Types have no completion barrier between them, and transfer
backlogs do not block computation workers. Faster servers consume
more work. There are no static per-server slices. Jobs use the
configured CPU allocation and bounded queues within the existing emitter. Scans use
smaller chunks for small counted checkpoints to keep CPU workers busy. Logs are
under the run directory, with one setup log per server and one log per job/server.
Set `td20.reconstructionAdaptiveScan=true` when tuning explicit lookup sizes to
retain that adaptation: small counted batches expose independent CPU tasks,
while large uncounted waves retain bulk reads.

The coordinator defaults to two download workers per server and a queue capacity
of twice the server count. Set `-R-Dtd20.fleetDownloadWorkers=N` and
`-R-Dtd20.fleetDownloadQueueCapacity=N` in its JShell profile to override them.
The bounded transfer queue is used only during explicit transfer phases, after
the corresponding computations have finished. Bulk transfers use independent
plain SSH connections with compression enabled and multiplexing disabled, so a server's
data streams do not share one ControlMaster connection. Existing configured
connections remain available for setup, uploads and computation logs.

After all emission computations, only task receipts and sealed endpoint residual
sets are downloaded to `RUN/results/TASK/`. These are required for inventory
validation and local inverse preparation. They contain no certificate database
and cannot claim certificate coverage. After all reconstruction computations,
every required certificate database is downloaded to `RUN/downloads/TASK/` and
validated before merging. Missing metadata, residual sets or certificates fail
their respective boundaries.

Profiles may be retuned before resuming; completed downloads are retained. Each
launch writes a versioned profile history, and each job attempt records its
configured/effective JVM flags and inspected hardware. The consolidated log also
records the hardware and effective profile per server. Worker output reports the
actual Java input arguments, CPU allocation and heap. Current task receipts use
format v2 and the fleet plan uses v2; incompatible old runs are rejected.

Follow **one consolidated local log**, live:

```bash
bash -ic 'tail -F fleet/run-01/emission.log'
```

It records phase changes and errors, with one compact status snapshot every ten
seconds. A snapshot identifies one of six production stages: remote emission,
receipt/residual downloads, local inverse preparation, remote reconstruction,
final certificate downloads, or certificate merge. It
reports elapsed time, completed/total tasks, remaining and queued tasks, active
servers, task throughput and failed attempts. Each active server has one row
showing its current computation, elapsed time and latest class counter, plus its
background transfer count and active download/validation stage. The summary
separately counts computing servers, queued computations and background downloads.
Task starts and completions do not trigger repeated fleet
dumps. Computation phases count closed remote jobs; transfer phases count
validated local results. These percentages count jobs; job sizes differ, and there is no invented
overall percentage or finish time while later inverse totals are unknown.

`RUN/status.txt` is an atomically replaced, versioned snapshot for an immediate
read without scrolling the log. All raw command output, including Bash job-control
warnings and JVM settings, remains in the per-task and setup logs. The consolidated
view suppresses those known Bash startup warnings and routine worker chatter;
errors and failed command exits remain visible. Independent proof verification
is explicitly pending even after production completes.
Reconstruction task totals become known after the first inverse wave is counted.
Local first-wave preparation and merge replay counters also appear in this log.
Resume appends to the same file and preserves previous attempts. Per-job logs
remain available for focused diagnosis.

The work has these boundaries:

1. The 83 root partitions have separate tasks. Every nonempty non-elementary
   endpoint annotation is split into a deterministic breadth-first frontier of
   generation prefixes, targeting `--endpoint-tasks` tasks per type (default 512).
   Each prefix is an independent subtree and creates local continuations for its
   server's CPU workers. Terminal classes emitted while constructing the frontier
   have explicit tasks too; none can disappear between prefix boundaries.
   Elementary closures and empty annotations retain explicit whole-type tasks.
   The union covers every annotation, and completed task counts must sum to its
   full Burnside inventory before reconstruction starts. Missing or duplicate
   task scopes fail validation. The existing root inventory checks remain active.
   A prefix descriptor is a sequence of child indices from the generator root.
   Each expansion replaces one subtree by its child subtrees and its directly
   emitted terminal classes. This preserves a disjoint complete cover by
   induction; terminal-output tasks ensure the boundary emits no class twice.
   The descriptors do not depend on a server's CPU count or scheduling order.
2. After all emission computations, the coordinator downloads only receipts and
   sealed residual sets, then validates all receipts
   and exact residual inventories, and forms their disk-backed union locally.
   Residuals come from exhaustive endpoint generation, never an obstruction list.
3. The coordinator generates the first inverse wave on all local CPU cores,
   restoring two fixed symbols in every allowed gap/support on either endpoint.
   The closed global wave and its AFTER_TWO checkpoint are atomically published
   under `RUN/reconstruction/waves`. Unmarked interrupted legacy waves are
   regenerated from the validated residual sets. Its sorted, unique after-two
   keys are split into disjoint counted batches by concurrent persistence workers.
   Small batches use one WAL-disabled RocksDB WriteBatch and a synchronous flush,
   retaining the existing checked state format. Each batch's database, task scope
   and checkpoint are atomically published together. Servers claim that batch
   immediately, while later batches are still being prepared.
   `td20.fleetBatchWorkers` defaults to the coordinator's available CPU count;
   `td20.fleetReadyBatches` defaults to eight ready batches per server. The bounded
   queue backpressures preparation while keeping computing servers supplied.
   Existing batches are reused only after matching every key against their
   regenerated deterministic slice. Reused closed results still require validated
   batch publication. Producer failure interrupts active dispatch; complete
   publication and the final counted batches.txt inventory are mandatory before
   final downloads and merging. Progress shows prepared/total batches and explicitly
   counts pending preparation separately from ready work.
   `--reconstruction-batch-size` sets the maximum classes per batch (default 64). A smaller
   size creates more independent tasks, with more repeated downstream work.
   This first-wave preparation is the legacy `FULL_SEARCH` route. With the
   active sealed residual input, the coordinator instead splits its complete set of
   after-two keys directly into counted batches under
   `RUN/reconstruction-residuals-v1`, without reconstructing the endpoint misses.
   The input path, count and mode are pinned in `reconstruction-input-v1.txt`.
4. Each active reconstruction job resumes a `RESIDUAL_EXPANSION` `AFTER_TWO`
   checkpoint. It restores either two or three fixed symbols, then one or zero,
   reconstructing both `(1,2,2)` and `(0,3,2)` paths on both endpoints. It
   expands every budget-ten intermediate state without lookup or search and
   searches the final roots at budget 11. The legacy `FULL_SEARCH` mode also
   searches inputs at budget 9 and intermediate states at budget 10. Fixed
   points are contracted before fixed-point-free state encoding.
   After residual intermediate generation, writers drain and a key-only
   RocksDB iterator scan counts the distinct budget-ten classes. The exact
   count is saved in the `AFTER_ONE` checkpoint before final expansion begins.
   This scan performs no state decoding, validation, prefetch, or additional
   Java deduplication. Legacy version-three `AFTER_ONE` checkpoints with
   `afterOne=-1` remain accepted only at a completed residual generation
   boundary; resume counts their persisted set and atomically upgrades the
   checkpoint before final generation. A deferred count cannot appear in
   legacy checkpoint versions or `FULL_SEARCH`.
   Final-wave progress reports completed expanded source classes as
   `processed=X/N (P%)`, using the exact fixed source total `N` for its
   completion fraction and rate-based ETA. Generated, submitted, consumed and SST-ingested output
   key occurrences accumulate during persistence and can include duplicates.
   The unique final-root count is established by the mandatory merged-database
   scan. Advisory prefetch reports submitted-so-far and pending occurrences
   without an expansion completion percentage.
   The independent proof checker retains its own regeneration and checks.
   A job fails if any final root remains unresolved.
5. After both remote computation phases, all completed witness databases are
   downloaded for the final merge. Cross-server duplicate classes
   are allowed; there is no shared remote database or global synchronization
   during reconstruction. Each job retains the existing disk-backed local
   deduplication. The final local streaming merge replays **every** input witness,
   retains the result with the lowest required budget per key, and breaks ties
   by unsigned encoded-value order. Bounded CPU tasks parallelize replay. Only
   one input database is open at a time, so thousands of small task results do
   not require thousands of simultaneously open RocksDB databases.

Remote jobs start with independent databases; certificates found by another job
are unavailable until merging. This can repeat searches, especially for overlapping
inverse descendants. Generation prefixes and reconstruction batches are the
scheduling units; free servers keep taking queued tasks while other machines
continue their current tasks. Tasks already running finish on their current
server. Increase `--endpoint-tasks` and decrease reconstruction batch size to
reduce the final tail, balancing smaller tasks against JShell/transfer overhead.
Tune task granularity using
observed job durations and utilization. The coordinator's first inverse wave is
local; the much larger remaining reconstruction waves run on the servers.

Each job has a versioned scope, deterministic identifier, exact input count, and
completion receipt published only after the emitter closes its databases. A
download is staged and atomically published after validation. Each closed remote
result has a versioned transfer intent under `RUN/transfers/PHASE/TASK.txt`, scoped
to this TD20 route, run, task, server and attempt. A transfer retries up to three
times without rerunning its computation. Resume retries persisted transfers from
their closed remote databases; missing transfer intents leave the corresponding
tasks as computation obligations. Missing or invalid completion receipts cannot
be accepted. An unfinished computation is regenerated in a fresh remote directory.
No partial output is merged. Remote work, failed attempts and local downloads are
retained for inspection; the coordinator does not remove server data. Downloaded
metadata under `RUN/results` is kept separate from full certificate downloads.
Full certificate acceptance still requires the actual RocksDB database; a
receipt-only result cannot pass that guard. Downloaded
reconstruction results contain the certificate DB and receipt, without the large
intermediate reconstruction sets and search queues.

After every expected job completes, a fresh final database is published atomically.
Its default destination is RUN/certificates; --database=certificates selects
the project-root directory. The destination is persisted per run and cannot
silently change on resume.
The residual route also accepts the already prepared project-root database:
it retains its emission/imported witnesses and obstruction markers, replays
the new results into it, flushes, and only then publishes fleet completion.
When `RUN/local-certificate-consolidation/complete-v1.txt` matches the run,
complete emission task/input scope and destination, and the destination's
original `consolidation-v1.txt` matches its historical counts, the coordinator
validates every original consolidated source but omits their duplicate witness
replay. It appends only the final reconstruction outputs and any separate
imported database. Added TD18 results and markers do not change the historical
emission counts used for this check. Missing preparation keeps every emission
input in the replay union; incompatible claimed completion fails selection.
These receipts are emitter resume records and cannot reduce independent checker
coverage or replace witness replay during proof verification.
An interrupted append is replayed from every input; the writer retains the
strongest existing result. `append-v1.txt` records witnesses replayed and newly
added during that append. When the imported reuse database remains separate,
the import record's database path is included in a fresh merge's input union.
Its `merge-v1.txt` records input witnesses, unique witnesses, and advisory marker
counts. In a fresh generic merge, obstruction markers are validated and omitted:
they have no explicit budget and are not proof inputs. Missing markers cannot
suppress any checker obligation. WAL is disabled for writes, and completed
persistence boundaries are flushed by the existing writer.

To merge other existing emitter databases independently:

```bash
bash -ic './tools/td20 merge --database=certificates /path/to/db-a /path/to/db-b'
```

The output must be fresh. Missing databases, incompatible schemas, malformed
keys, invalid witnesses and corrupt duplicate records all fail the merge.
Certificates use normalized cyclic-target pairs and their existing deterministic
keys; cross-server deduplication keeps the strongest intrinsic witness, which is
necessary when an earlier inverse wave has already relied on a smaller budget.

Closed emission databases can instead be physically consolidated on their source
servers before downloading. This reduces thousands of small file transfers:

```bash
bash -ic './tools/td20 fleet-consolidate --run=fleet/run-01 --servers=fleet/servers.tsv'
```

Stop all computation and earlier download coordinators first. This command
requires a complete, validated local emission receipt/residual inventory and
every original closed remote database. It re-inspects the sources, applies each
profile with automatic NUMA flags, and runs raw KV copying on all servers in
parallel. Disjoint sorted ranges are copied into external SSTs without decoding
or replaying witnesses. Native compaction targets 1 GiB ZSTD SSTs. Repeated schema
records are written once; unexpected duplicate class keys or missing KV records
reject publication. This path applies to disjoint root/endpoint emission tasks,
not overlapping inverse reconstruction results. Source databases are preserved.

The consolidation source snapshot and ten-second log/status snapshots are under
RUN/consolidation, separate from the original computational snapshot. Downloads
use 24 independent SSH workers, with a bounded queue of 48 and round-robin server
ordering. SSH recompression is disabled for the already compressed SSTs. Progress
includes transferred bytes, current throughput, retries and transfer ETA. Stable
per-server staging retains complete SSTs across retries. Exact file sizes, task
receipts, schema, counts and RocksDB checksums are checked before publishing
RUN/downloads/SERVER/certificates. All expected tasks are mandatory in
RUN/consolidation/complete-v1.txt. These operational checks do not replay witnesses
or establish proof coverage.

A subsequent fleet resume validates and reuses the consolidated emission scope
without redispatching emission. Its final replaying merge combines these DBs
with every required reconstruction result. Independent full checking stays
separate. Keep the source servers until consolidation downloads are fully
accepted locally; completed computations and prepared reconstruction inputs
are unaffected.

Fleet receipts and checkpoints are operational records, never proof inputs.

If one source server is irrecoverable, keep all accepted local databases and
the original full task/residual inventory. Recover that server's exact selected
emission scope with the surviving server profiles:

```bash
bash -ic './tools/td20 fleet-recover-emission --run=fleet/run-01 --servers=fleet/survivors.tsv --lost-server=lost-node'
```

The selected scope comes from RUN/consolidation/lost-node-inputs-v1.txt and is
checked against the full emission plan and original receipts. This command
never starts inverse reconstruction. It schedules the lost emission tasks in
the existing shared queue, checks regenerated receipts and residual classes,
consolidates the replacement data remotely, and resumes interrupted SST
transfers without discarding saved bytes. Replacement inputs have separate
recovered-lost-node-SERVER directories. Complete combined coverage is required
before adopting the replacement emission backup; partial SST databases cannot
be used. Clean old remote work only after surviving databases are accepted
locally and every computing worker has stopped.

They do not establish exhaustive proof coverage. Run the independent checker
against the merged database, using fresh checker work and the full scope:

```bash
bash -ic './tools/td20 verify --phase=all --database=certificates --work=avoidance-work/checker'
```

The checker remains independent of the emitter and regenerates every obligation
and all inverse waves. Keep every exit criterion in [RESEARCH_PLAN.md](RESEARCH_PLAN.md).
All generated fleet plans, databases, checkpoints, transfers and ordinary logs
belong under ignored fleet/data/work directories or outside the repository,
away from Maven target directories.
