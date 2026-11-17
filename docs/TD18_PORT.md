# Complete TD18 improvement port

Scope: TD20 baseline **965b47475b483925db26ef72a0e5199d68225dda**
(13 November 2026, 08:21:04 UTC) through TD18 HEAD
**22042d8d2274ce05ce1c7868cedab66937eb3ebe** (16 November 2026,
11:08:17 UTC). The corresponding TD18 baseline is
**6b1585c943f4c74b12cc13fbff804be59c2b93f3**, its commit defining the
Java CPU prefix-depth default at the same timestamp. The repositories have
distinct commit hashes. All **39 subsequent TD18 commits** are accounted for
below, including final supersession of temporary tuning and deployment choices.

TD20 commit **84cd4b5eead5adbeea11e5133b4e277af5f8005d** already ports the
runtime and mathematical changes through `7a729c4`. This follow-up reviews
all remaining source, test, tool and documentation differences, extends the
checked comparison to tests and launchers, and incorporates `22042d8`.

The user explicitly authorized porting the Eriksson route on 16 November 2026,
replacing the former joint-2 instructions. TD20 keeps its own diameter premise,
domain, budgets, explicit budget-valid support clearings and conditional proof
status. Every current-route inventory must be regenerated. Historical joint-2
counts and TD18 reference artifacts cannot discharge TD20 obligations.

| TD18 commit | Improvement | TD20 disposition |
| --- | --- | --- |
| `4bfbb11` | Use the Eriksson reduction and checked endpoint inventories | Ported the two-sided Eriksson three-move route, gain-zero first moves, independent root and endpoint inventories, and three inverse waves. Adapted to 21-symbol roots / budget 11, 16-symbol endpoints / budget 8, 83 partitions / 143 annotations, and TD20 support clearings. TD18 counts are replaced by freshly regenerated TD20 inventories; incomplete counts remain explicitly pending. |
| `6f8e15e` | Decouple reconstruction CPU searches from GPU dispatch | Ported separate CPU prefix and GPU dispatch pools with disk-backed reconstruction handoff. |
| `14263b4` | Share reconstruction state encoding across pipeline queues | Ported shared versioned state encoding for emitter pipeline queues; checker retains independent encoding. |
| `32d0a36` | Search reconstruction prefixes to depth five and exit GPU seeding on closure | Ported reconstruction CPU depth five and first-seed GPU diameter exits. Preserved the TD20 general CPU deadline in addition to reconstruction overrides. |
| `cd17d1c` | Bound intermediate GPU reconstruction searches and preserve obligations | Ported explicit unresolved search outcomes and preservation of all inverse branches. The temporary intermediate depth cap was later superseded by full-budget search. |
| `970dc43` | Accelerate capped CUDA search and batch inverse persistence | Ported capped CUDA terminal scans, all-odd pruning, batched inverse persistence, and their correctness/validation requirements. |
| `f88bad0` | Shard inverse persistence with bounded lock-free queues | Ported deterministic persistence shards, bounded lock-free producer queues and asynchronous failure propagation. |
| `9af1e75` | Decouple GPU misses from inverse work and report wave progress | Ported a separate CPU inverse pool and GPU-miss disk queue, complete coverage accounting and per-wave progress. |
| `be93364` | Make inverse handoff batches configurable and increase to 65536 keys | Ported configurable inverse handoff batches, default 65,536 keys. |
| `43c10af` | Persist inverse waves through independent sorted SST builders | Ported independent sorted SST builders, WAL-free ingestion and deterministic key deduplication. |
| `ac28252` | Batch across inverse cases and prefetch root GPU searches during reconstruction | Ported producer batches retained across inverse callbacks and bounded advisory root GPU prefetch. |
| `68b2db5` | Fill source replay gaps with bounded advisory root GPU slices | Ported positive-time-slice advisory full-depth root searches; misses cannot remove final scan obligations. |
| `31ec858` | Move per-table inverse persistence logs to debug | Ported per-table persistence details at DEBUG log level. |
| `e0219f1` | Prepare 16 GPU slots per P100 for the next reconstruction restart | Temporary P100 slot preset is superseded by the maintained configurable launchers and later preset removal; no obsolete machine preset retained. |
| `af49bde` | Remove reconstruction sealing barriers and feed GPUs from prepared work | Ported prepared-work root prefetch and removal of redundant reconstruction sealing/count barriers; exact count is established by the required processing scan. |
| `f0211a2` | Load bashrc for server operations and P100 launches | Ported the bashrc/toolchain-loading operating convention and plain SSH guidance. Obsolete P100 launcher details were superseded. |
| `8a58d4d` | Use configurable 512K batches across database writers | Ported configurable 524,288-entry database batch limits across writers. |
| `41516be` | Skip redundant certificate reads during emission and reconstruction sorting | Ported fresh emission search and batch replay/miss dispatch without redundant per-class reads. |
| `4673606` | Label emission, persistence and inverse reconstruction progress accurately | Ported distinct emission, state/certificate persistence, inventory and inverse progress labels; unknown totals remain explicit. |
| `c528fa8` | Parallelize reconstruction scans and integrate bounded CUDA state caching | Ported parallel encoded-state scans and bounded exact-state CUDA depth-dominance caching. |
| `13caf28` | Fix resumed scans, GPU memory preflight, and log timestamps | Ported resumed-scan validation, native exact memory preflight and timezone-bearing timestamps. |
| `7d6e061` | Batch independent checker persistence and parallelize scratch scans | Ported independent checker sharded SST persistence, bounded producer batches and parallel scratch scans. |
| `dbb8e77` | Adapt checker batch granularity to small inverse inventories | Ported adaptive checker batch granularity for small inverse inventories. |
| `256aa02` | Prepare workspace-backed RTX 5090 emitter and checker launch profiles | Ported workspace-backed emitter/checker launch infrastructure; temporary RTX 5090-specific profile values were superseded by later configurable local defaults. |
| `6a60ce9` | Parallelize ordered certificate write batches without WAL | Ported ordered parallel certificate writers with WAL disabled. |
| `3518b09` | Ingest certificate-only batches as sorted tables without WAL | Ported certificate-only sorted-table ingestion without WAL. |
| `1a6a7c7` | Keep unmarked certificates on the table path in mixed batches | Ported mixed-batch handling that keeps unmarked certificates on the sorted-table path. |
| `fbc3f99` | Optimize checker orbit canonicalization with primitive comparisons | Ported independent checker primitive canonicalization and full-word equivalence tests. |
| `6f89aef` | Document checker canonicalization equivalence and measured speedup | Ported canonicalization equivalence explanation; TD18 measured speedup is not claimed as a TD20 measurement. |
| `1f4806a` | Remove completed legacy canonicalization comparison benchmark | Completed legacy benchmark is absent from the final tree; no transient comparison executable added. |
| `81e006d` | Remove all benchmark tools, support reports and documentation references | Removed obsolete benchmark/audit tooling and references. Superseded joint-2 census helpers are replaced by current-route JShell census/publication scripts. |
| `c9134b2` | Keep only RTX 5090 and EPYC 7742 simulation profile | Temporary RTX 5090/EPYC profile consolidation was superseded by the final maintained local profile; no obsolete deployment preset retained. |
| `847219a` | Optimize checker key encoding and certificate replay | Ported direct canonical key packing, exact canonical-frame reuse, single replay-derived budget validation and independent inverse target reuse. |
| `3ee6d12` | Preserve strongest sorting certificates across write batches | Ported strongest sorting-certificate retention across write batches and regression coverage. |
| `5871a30` | Record the local reference emission and document the TD18 proof | Ported execution settings/timing/provenance recording and the explicit validation guide. TD18 proof claims, counts, raw reference logs and hardware timings remain TD18 evidence; TD20 stays conditional and all generated logs remain untracked. |
| `29aedcd` | Align runtime profiles with the local reference hardware | Ported bounded maintained local launchers, affinity/quota-aware CPU detection, architecture overrides, preflight and success-marker checks. Machine-specific CPU worker counts are detected rather than hardcoded. |
| `47318e0` | Remove obsolete machine deployment presets | Removed obsolete machine deployment presets; maintained local launchers are the supported entry points. |
| `7a729c4` | Refresh compiled TD18 proof PDF | Regenerated the TD20 reading PDF from the updated TD20 source paper; no TD18 PDF or proof claim copied. |
| `22042d8` | Record successful independent reference verification | Recorded the completed TD18 emission and separate-machine full-scope check as provenance for the established TD18 premise. Ported the separation of emission, verification, test and census evidence in the TD20 execution checklist. TD18 counts, timings and retained logs describe its own run; TD20 full verification remains pending. |

## Port boundaries and verification

All current TD18 runtime, persistence, scheduling, counting, sorting, native,
checker, test, command and documentation changes are included or accounted for
by an explicit final-state supersession above. The independent checker remains
free of an emitter artifact dependency. Intrinsic certificate key bytes remain
compatible; active route checkpoints and count reports are scoped to TD20 and
reject superseded routes. Generated databases, reports, settings and logs remain
untracked.

The source paper now defines 83 root partitions on 21 symbols and the full
143-annotation endpoint cover on 16 symbols. The active budgets are 11 for
direct/final roots, 8 for endpoints, 9 after two moves, and 10 after one move.
The two after-one domains are 20 and 21 symbols. The allowed local powers remain
9:7, 11:6, 13:7/10/11 and 17:9/13/15; the TD18 length-seven clearing is not
budget-valid for TD20.

Required validation is documented in [VALIDATION.md](VALIDATION.md). Structural
inventory checks, small-domain complete class-set comparisons, CPU suites and
native checks validate implementation behavior within their executed scopes.
They do not substitute for full current-route counts, sorting or independent
end-to-end proof verification. [RESEARCH_PLAN.md](RESEARCH_PLAN.md) retains every
completion criterion.

## Complete source, test and tool comparison

The audit compares **181 shared files** at the reviewed TD18 revision: 113
production/build/native/resource files, 60 Java test files, seven maintained
tools and `.gitignore`. It applies frozen, reviewed production and test/tool
adapter patches before comparing every file exactly. There are no excluded
runtime algorithms, tests or upstream launchers. The only additional TD20 tools
are this port's audit, its regression tests, the isolated root-publication
regression and the reviewed adapter specifications.

The execution adapter records the separate RTX 4080 Super launcher, SM 89
build support and the emitter's published-zero root and endpoint scheduling
shortcuts. The endpoint reader requires the complete versioned paper census
and retains generated-count checks for every nonempty type.
Every added launcher is compared byte for byte with its frozen adapter and
its executable mode is checked. Zero-direct rows remain mandatory independent
checker generation and count-comparison obligations.

The reviewed test/tool adapter also records a TD20 publication safeguard in
`CountErikssonRoots.jsh`. Unlike the upstream row-only publisher, it validates
all 83 versioned reports, partition order and table markers before atomically
updating the rows, aggregate totals and count-status paragraph. It reparses the
temporary inventory before replacement, rejects incomplete or incompatible
data and arithmetic overflow, and preserves the conditional proof status.
Publishing emitter counts remains separate from independent regeneration and
full sorting verification. The TD20-only `test_eriksson_root_publication.py`
fixture checks every synthetic row, all four totals, unchanged proof text,
idempotent publication, malformed or incomplete reports, displaced rows,
partition order, overflow and temporary-file cleanup. Its 131 checks and a
separate checker publication guard pass entirely in temporary directories.

Test adaptations preserve all upstream regressions while covering the TD20
21-symbol domain, budget eleven, admitted length-17 powers, rejection of the
superseded joint-2 schema and the general CPU deadline. The certificate backup
directory is ignored along with all generated TD20 logs and settings. The
audit rejects missing or extra shared files, changed content, stale reference
revisions, unaccounted commits and surviving tools retired anywhere in the
reviewed history. Documentation and the proof paper are reviewed separately
because their inventories, premises and completion status belong to TD20.

The final upstream commit records a separate-machine checker finishing on
16 November 2026 at 10:11:21 Lisbon time, in 1 h 45 min 43 s, with exit zero
and 1,349,910,890 reconstructed TD18 roots verified. Its
[revision-pinned record](https://github.com/luizaugustogarcia/td18/blob/22042d8d2274ce05ce1c7868cedab66937eb3ebe/docs/REFERENCE_SIMULATION.md)
retains both execution configurations and logs. Tests were skipped for that
verification run and standalone census reports were not republished; those
remain separate checklist items. The TD20 checklist retains every corresponding
requirement, with generated artifacts untracked. TD(16) = 9 and TD(18) = 10
are established results using the same computational methodology.

## Distributed extension moved to TD20 (17 November 2026)

The distributed SSH implementation developed at TD18 revision
`ad247baafb9af2666b01148db0455305d90f0264`, including subsequent local logging
and source-preparation improvements, now belongs to TD20. The TD18 main branch
was explicitly restored and force-pushed to
`701dce95bd225e3ea5d0bbcb937ddd826f18b55a`; both local TD18 checkouts are clean.

The extension preserves TD20’s 83-root/143-endpoint scope and budgets 8/9/10/11,
its existing certificate schema and independent checker. It includes prefix
scheduling, reconstructed-state batches, SSH transport, per-server profiles,
NUMA flags, versioned task receipts, staged downloads, deterministic strongest
witness merging and compact status logging. No TD18 count or run output is reused.

The Java build passed after migration; the operator requested no startup tests
or replay. The frozen comparison described above predates these added files and
shared helper changes. Its existing adapters and test/tool ledger have not been
updated or rerun for this extension, so no new exact-comparison success is claimed.
See [SESSION_HANDOFF.md](SESSION_HANDOFF.md) for configuration, the stopped state,
and the fresh TD20 production continuation.

The operator subsequently authorized a TD20 scheduling extension: completed
remote results download and validate through a bounded background queue while
servers claim their next computation. Transfer intents support retries and
resume without recomputing a closed result, and phase barriers still require
every accepted receipt. This explicitly differs from the migrated synchronous
download scheduler; the closed TD18 checkout is not modified. The frozen audit
does not certify this extension. Independent proof verification remains separate.

The operator then authorized deferring all certificate downloads until the final
merge, with only receipts and sealed endpoint residual sets transferred before
inverse preparation. Computation queues no longer depend on transfer queue
capacity. Completion metadata is validated separately and cannot satisfy the
existing guard requiring an actual certificate database. Bulk transfers use
dedicated parallel compressed SSH connections. Both Maven CPU suites passed for
this change; no frozen port-audit or full TD20 proof-check success is claimed.
