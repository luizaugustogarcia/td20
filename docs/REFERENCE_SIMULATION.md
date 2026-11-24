# TD(20) = 11: completed proof execution

The completed full-scope independent check establishes TD(20) <= 11.
Together with the established reverse-permutation lower bound, it proves
**TD(20) = 11**. [The proof completion record](RESEARCH_PLAN.md) maps the
mathematical derivation, CPU validation, independent inventories and witness
coverage to their checked evidence.

The EC2 run `checker-20261122T183611Z-32945` completed full-scope independent
verification on 2026-11-22 with exit code 0, `status=complete`, and
`completionScope=complete`. The run printed the application success marker
“All regenerated obligations in the complete joint-selection scope are verified.”
All 83 root rows and 143 endpoint annotations have completion entries.

The run used fresh work and no resume. The public certificate database used to
reproduce the check is linked from the repository [README](../README.md).

Execution started at **2026-11-22 18:36:11.217288 UTC** and finished at
**22:57:16.523331 UTC**, an elapsed **4 h 21 min 5.306043 s** including startup
and shutdown. Recorded phase boundaries place root regeneration at 18:36:13.206342809–
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
and budgets 8/9/10/11. Source provenance is `source-export`; executed Java
class hashes supplied the implementation provenance rather than a Git revision.

CPU validation reported zero failures and errors. The completed standalone root
census agrees on all 83 rows, and the endpoint census covers all 143
annotations.
TD(16) = 9 and TD(18) = 10 are established premises.

The 23 November 2026 CPU validation reported 301 emitter tests, zero
failures or errors and 16 skipped device tests; checker reported 171 tests, zero
failures, errors or skips. The skipped CUDA checks are not counted as passed
checks. The root-table publication regression also passed.

For reproduction, record the actual allocation, effective settings, source
revision, database and report locations, timestamps, exact regenerated counts,
unresolved classes, phase durations, application success marker and exit status.
The checker must use fresh work and independently replay every obligation.
Generated databases, logs, settings and count reports remain untracked.
