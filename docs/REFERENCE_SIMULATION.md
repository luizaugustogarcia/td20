# TD20 execution record

Instance 61821972 (host 51010, machine 153507) is the retained execution
instance. Its checker configuration is in [OPERATIONS.md](OPERATIONS.md),
and its certificate-transfer record is in [SESSION_HANDOFF.md](SESSION_HANDOFF.md).

The EC2 run `checker-20261122T183611Z-32945` completed full-scope independent
verification on 2026-11-22 with exit code 0, `status=complete`, and
`completionScope=complete`. The log contains the application success marker
“All regenerated obligations in the complete joint-selection scope are verified.”
All 83 root rows and 143 endpoint annotations have completion entries.

The retained local evidence directory is
[`logs/official-verification/checker-20261122T183611Z-32945/`](../logs/official-verification/checker-20261122T183611Z-32945/).
It contains the original timestamped log and settings JSON, console log,
`inverse-coverage-v3.txt`, the actual `run-checker-tuned.sh` launch profile,
an after-completion `machine.txt` hardware snapshot, and `EXECUTION.txt` with
the full recorded JShell options, effective system properties, and invocation.
These generated artifacts remain untracked. The remote originals are under
`ubuntu@3.137.201.7:/mnt/td20-fast/td20/local-runtime/`; the certificate database
is `/mnt/td20-fast/td20/certificates`. The run used fresh work and no resume.

Execution started at **2026-11-22 18:36:11.217288 UTC** and finished at
**22:57:16.523331 UTC**, an elapsed **4 h 21 min 5.306043 s** including startup
and shutdown. Log boundaries place root regeneration at 18:36:13.206342809–
19:16:19.019893539 UTC (40 min 5.814 s), endpoint verification at
19:16:19.021028746–20:00:10.986666314 UTC (43 min 51.966 s), and the final
reconstructed-root scan completion at 22:56:35.801 UTC.

The machine was EC2 host `ip-172-31-43-233` (`3.137.201.7`), with Intel Xeon
Platinum 8375C CPUs: 2 sockets, 24 cores per socket, 2 threads per core,
96 logical CPUs and 2 NUMA nodes; approximately 743 GiB RAM and no swap.
Six 3.4 TiB NVMe instance-store disks were attached; the run and database used
the ext4 volume `/dev/nvme1n1`, mounted at `/mnt/td20-fast`.
The runtime was OpenJDK 21.0.13.1 on x86_64 Linux.

Effective execution parameters were:

| Parameter | Value |
| --- | --- |
| Scope | `verify --phase=all`, no partition or endpoint filters |
| Checker / bracelet / read workers | 96 / 96 / 96 |
| JVM | Xms 16 GiB, Xmx 64 GiB, G1, UseNUMA, ActiveProcessorCount 96 |
| GC threads | 16 parallel, 4 concurrent; target pause 200 ms |
| Reconstruction writers | 32 |
| Certificate lookup / producer batches | 4,096 / 1,024 keys |
| Certificate admitted buffers | 8,192 MiB |
| Certificate read cache / shared RocksDB budget | 131,072 MiB; shared, not additive |
| RocksDB write-buffer budget | 16,384 MiB within managed budget |
| RocksDB cache | 64 shards, strict capacity, metadata cached, high-priority ratio 0.5 |
| RocksDB read policies | Fill cache for root, endpoint and reconstruction reads |
| RocksDB memtables | 256 MiB, at most 4 buffers per scratch DB; write stalls enabled |
| Open files | Certificate 4,096; scratch 1,024; process limit 65,536 |
| Reconstruction buffer / bounded queue | 8,388,608 keys / 128 batches |
| Generated / scan / inverse scan batches | 65,536 / 4,096 / 16 keys |
| Scan ranges per worker | 16 |
| Database write batch | 131,072 entries; WAL disabled |
| Progress | 10 seconds, memory reporting enabled |

The version-three inverse report records 2,036 residual endpoints;
5,032,950 after-two classes, including 4,062,619 closed and 970,331 residual;
4,010,256,622 after-one classes; 51,456,682 residual roots; and **0 unresolved**.
It records `rootInventory=ELEMENTARY_RESIDUALS`, `budgetTenLookup=false`,
and budgets 8/9/10/11. Source provenance is `source-export`; the settings JSON
retains executed Java class hashes rather than a Git revision.

No Maven tests were run in this execution session, at the user's request.
The matching upper bound remains pending until every criterion in
[RESEARCH_PLAN.md](RESEARCH_PLAN.md) has a checked artifact.
TD(16) = 9 and TD(18) = 10 are established premises.

For this instance, record the actual allocation, effective settings, source
revision, database and report locations, timestamps, exact regenerated counts,
unresolved classes, phase durations, application success marker and exit status.
The checker must use fresh work and independently replay every obligation.
Generated databases, logs, settings and count reports remain untracked.
