# Emitter: component interactions during execution

The emitter generates obligations, searches for intrinsic results, persists
checked results, and expands misses into inverse work. This walkthrough follows
the full fresh production execution. See the
[visual overview](EXECUTION.md) for data representations and the
[checker walkthrough](CHECKER_EXECUTION.md) for independent verification.

## 1. The coordinator owns the phase sequence

[WitnessEmitter](../emitter/src/main/java/br/unb/cic/td20/WitnessEmitter.java)
only forwards to
[ErikssonWitnessEmitter](../emitter/src/main/java/br/unb/cic/td20/emission/ErikssonWitnessEmitter.java).
The latter reads inventories, creates the run's resources, installs the
proof-scoped writer in `WitnessSearch`, and advances through these phases.

```mermaid
flowchart TB
    M["ErikssonWitnessEmitter.main"] --> I["Read complete root and endpoint inventories"]
    I --> O["Open AsyncCertificateWriter<br/>Create endpoint residual set"]
    O --> W["WitnessSearch.installWriter"]
    W --> R["emitRootPrimitive<br/>Process selected root rows"]
    R -->|flush each completed row| E["emitEndpointPrimitive<br/>Process endpoint types"]
    E -->|flush each completed type| A["ErikssonAvoidance.resolvePrimitive<br/>Three inverse waves and final sorting"]
    A --> F["Flush results<br/>Require zero unresolved final roots"]
    F --> C["Uninstall writer and close resources"]
```

Each phase uses callbacks: the generator owns enumeration; the coordinator
supplies what to do with a generated pair. `settle(pair, budget, ...)` is the
ordinary discharge callback. Reconstruction instead supplies separate lookup,
CPU, GPU and persistence-barrier callbacks to `ErikssonAvoidance`.

An emission completion message reports its requested scope. Independent checking
is still needed, even when every search callback reports success.

## 2. Root generation hands only direct obligations to sorting

`emitRootPrimitive()` reads the row's complete counts. A published zero-direct
row is skipped for emission scheduling. The checker independently regenerates
that row; the skip is not proof that the generator would find nothing.

For a nonzero row,
[ErikssonRootGenerator](../emitter/src/main/java/br/unb/cic/td20/joint/ErikssonRootGenerator.java)
starts bounded parallel rank-placement tasks. Prefix state contains partial
positions, algebraic images, target edges and precomputed move predicates.

```mermaid
flowchart TB
    Row["Root inventory row"] --> Z{"Published direct count zero?"}
    Z -->|yes| Skip["Skip emitter traversal for this row"]
    Z -->|no| G["ErikssonRootGenerator.generatePrimitive"]
    G --> P["Prefix placement<br/>Target feasibility, orbit comparisons, route predicates"]
    P --> Leaf["Surviving full leaf<br/>Exact two-sided selection test"]
    Leaf --> Ret["Retained root<br/>Increment unoriented or oriented count"]
    Ret --> SC{"SupportClearing.closes?"}
    SC -->|yes| Cleared["Increment support-cleared count<br/>No sorting task"]
    SC -->|no| Task["BoundedTasks.submit<br/>Canonicalize pair, budget 11"]
    Task --> Settle["settle: WitnessSearch.searchFresh"]
    Settle --> Done["Count settled or unresolved"]
    G --> Compare["After generation joins:<br/>compare all three active counts with paper row"]
    Compare --> Join["Close BoundedTasks:<br/>join all discharge tasks"]
    Join --> Boundary["Flush results; reject unresolved direct roots"]
```

The exact selection test covers immediate gains on either endpoint, every
gain-zero/one first move, the required second moves and every eligible third
move. Prefix rejections can recognize some of these routes earlier; the final
leaf test handles the remaining two-sided possibilities.

[SupportClearing](../emitter/src/main/java/br/unb/cic/td20/joint/SupportClearing.java)
is a separate retained-root filter. Its allowed powers are precisely
9:7, 11:6, 13:7/10/11 and 17:9/13/15. The generator's counts include those
cleared roots; the callback keeps them out of the sorting queue. The row
comparison checks retained unoriented, retained oriented and support-cleared
counts, rather than comparing only their final difference.

Root representatives are unique within their type but use a different minimum
from certificate keys. `canonicalRepresentative()` resolves that difference
before `searchFresh()`.

## 3. Endpoint generation classifies every emitted representative

`readEndpointWork()` reads the complete published endpoint table and associates
each count with its independently constructed annotated type.
`emitEndpointPrimitive()` skips zero-count types and types with an elementary
closure. Other types use the direct twisted-bracelet generator.

```mermaid
flowchart TB
    T["EndpointWork<br/>Annotated type and published class count"]
    T --> S{"Zero count or elementary closure?"}
    S -->|yes| End["Finish this type without sorting"]
    S -->|no| G["DirectTwistedBraceletGenerator<br/>Concurrent representative stream"]
    G --> C["Consumer: settle<br/>Canonical pair, budget 8"]
    C --> R{"Budget-eight discharge found?"}
    R -->|yes| OK["Checked result available"]
    R -->|no| Miss["DiskStateSet.add<br/>endpoint-residuals, budget 8"]
    OK --> Count["Increment generated count"]
    Miss --> Count
    Count --> B["After all generator callbacks finish:<br/>compare generated count and flush writer"]
```

During ordinary emission, the unique generated class is searched directly; a
preliminary database lookup would duplicate work on every class. If an optional
`ReadOnlyWitnessSource` is supplied, `settle()` can replay and reuse a compatible
result before searching.

When running avoidance from existing results, endpoint generation still occurs,
but `settle()` checks the current database without launching ordinary endpoint
sorting. Its misses rebuild the endpoint residual set. A stored obstruction
marker is not used to decide whether to enumerate an endpoint.

Source anchors:
[DirectTwistedBraceletGenerator](../emitter/src/main/java/br/unb/cic/td20/bracelet/DirectTwistedBraceletGenerator.java),
[ReadOnlyWitnessSource](../emitter/src/main/java/br/unb/cic/td20/certificate/ReadOnlyWitnessSource.java),
[DiskStateSet](../emitter/src/main/java/br/unb/cic/td20/avoidance/DiskStateSet.java).

## 4. One sorting obligation: CPU, CUDA, replay, writer

[WitnessSearch](../emitter/src/main/java/br/unb/cic/td20/search/WitnessSearch.java)
is the boundary between search results and persisted certificates. Searchers
return a candidate result; Java replay checks it before the writer accepts it.

```mermaid
%%{init: {"sequence": {"wrap": true, "width": 120, "actorMargin": 30}}}%%
sequenceDiagram
    participant Caller as Discharge callback
    participant Search as WitnessSearch
    participant CPU as CpuPrefix<br/>Search
    participant GPU as GPUSorting<br/>Search
    participant Validator as Certificate<br/>Validator
    participant Writer as AsyncCertificate<br/>Writer
    Caller->>Search: searchFresh(pair, budget)
    Search->>CPU: Search prefix in worker-owned arrays
    alt CPU finds complete sorting or diameter closure
        CPU-->>Search: PrimitiveCertificate
    else CPU misses or reaches a limit
        CPU-->>Search: No result
        Search->>GPU: Original pair and full obligation budget
        GPU-->>Search: Complete / closure / residual / timeout / depth limit
    end
    alt A result exists
        Search->>Validator: Replay moves and verify required budget
        Validator-->>Search: Validated result
        Search->>Writer: enqueue(pair, result, budget)
        Writer-->>Search: Admitted, surface any writer failure
        Search-->>Caller: CERTIFICATE or DIAMETER_CLOSURE
    else No result
        Search-->>Caller: RESIDUAL
        Caller->>Writer: enqueueObstruction(pair, budget)
    end
```

[CpuPrefixSearch](../emitter/src/main/java/br/unb/cic/td20/search/CpuPrefixSearch.java)
performs recursive apply/rollback search in a thread-local workspace. It tests
identity and known-diameter closure of the actual remaining nonfixed domain.
Depth, triple-count and time limits bound this heuristic. A CPU miss sends the
**original obligation** to the GPU; the prefix is not charged as completed work
and its last visited state is not the GPU's starting point.

Direct search defaults to a CPU prefix depth of three; reconstruction's
`searchCpuMiss()` supplies a default depth of five. Effective settings can
override depth. These heuristic depths differ from the full obligation budget.

`searchFresh()` omits stored-result lookup. General `search()` can replay an
existing result first. Reconstruction uses `lookupPrimitiveBatch()` before CPU
work and then `searchCpuMiss()` / `searchGpuMiss()`, which avoid repeating that
lookup. GPU-only callbacks also avoid rerunning the CPU prefix.

The writer's admission is asynchronous. It does not mean the result is durable
on disk yet; explicit phase flushes establish that boundary.

Inside the CPU prefix, the reusable `Workspace` owns one mutable `omega` vector,
a `beta` order per depth, and a packed move stack. The recursive interaction is:

```mermaid
flowchart TB
    Visit["Workspace.visit(depth, fixed, odd)"]
    Visit --> Exit{"Identity or known-diameter exit?"}
    Exit -->|yes| Result["Copy move-stack prefix<br/>Return PrimitiveCertificate"]
    Exit -->|no| Limit{"Time, depth, triple or bound limit?"}
    Limit -->|yes| Miss["Return false to caller"]
    Limit -->|no| Moves["Try consecutive supports first,<br/>then gain-one, then gain-zero supports"]
    Moves --> Apply["visitMove: save and update 3 omega images"]
    Apply --> Allowed{"Allowed odd-cycle-count change?"}
    Allowed -->|yes| Child["Write beta at next depth and packed move"]
    Child --> Recurse["Recursively visit child"]
    Allowed -->|no| Rollback["Restore saved omega images"]
    Recurse --> Rollback
    Rollback --> Next{"Result found?"}
    Next -->|yes| Return["Return success up the call stack"]
    Next -->|no| Moves
```

The parent `beta` remains in its depth row while the child uses the next row.
Restoring the three changed images makes the next support start from the same
parent. If all permitted supports finish without a result, `visit()` returns
false. No intermediate workspace state is persisted as a successful result.

## 5. Inside the GPU handoff

Java's
[GPUSortingSearch](../emitter/src/main/java/br/unb/cic/td20/base/GPUSortingSearch.java)
owns a bounded pending-search queue and a worker per configured device slot.
`GpuDeviceLayout` maps slots to devices. The first GPU search initializes the
native resources; successful CPU-only work does not initialize CUDA.

```mermaid
flowchart TB
    Java["WitnessSearch<br/>Pair bytes, budget, limits"] --> Queue["GPUSortingSearch.pendingSearches<br/>Bounded SearchJob queue"]
    Queue --> Slot["Java slot worker<br/>runSlotWorker(slot)"]
    Slot --> JNI["jni_sorter.cu<br/>searchForSortingSeqWithLimits"]
    JNI --> Manager["slot_manager.cu<br/>Slot's device, stream and resources"]
    Manager --> Init["sorting_search.cu<br/>Initialize input and device queues"]
    Init --> Kernel["Persistent sorting kernel<br/>Warps expand queued search states"]
    Kernel --> Result["Copy result after device completion"]
    Result --> Future["Complete SearchJob future"]
    Future --> Replay["Java packs result and replays it"]
```

Within a slot, the kernel moves through queued tree states. It uses cooperative
warp copies, local/overflow queues, outstanding-work accounting and a bounded
state/depth cache. A found sorting or known-diameter closure returns a move
sequence. Queue overflow is an error. Time/depth limits and exhaustion of the
restricted tree return a miss, which leaves the obligation unresolved.

The persistent kernel's work cycle is different from the CPU call stack:

```mermaid
flowchart TB
    Pop["Warp takes parent from local queue;<br/>otherwise steals or checks overflow queue"]
    Pop --> Exit{"Parent already closes?"}
    Exit -->|yes| Publish["Publish result kind and move history"]
    Exit -->|no| Prune{"Depth/bound failure or cached domination?"}
    Prune -->|yes| Retire["Retire outstanding parent work"]
    Prune -->|no| State["Reconstruct beta from omega and fixed rho<br/>Collect nonfixed support positions"]
    State --> Fast["Try fast one-move exits"]
    Fast -->|found| Publish
    Fast -->|none| Children["Lanes evaluate permitted general moves"]
    Children -->|found exit| Publish
    Children -->|continuing states| Queue["Cooperatively copy child batches<br/>Fence writes, publish queue entries"]
    Queue --> Retire
    Retire --> Pop
```

Some exits also occur during initial queue seeding. A warp with no available
parent waits or steals while outstanding work remains; zero outstanding work
ends the search. The target is fixed for a slot's search, so queued states can
store algebraic images and reconstruct the current cycle when needed. The
cache compares full state keys and depths; collisions or full probe sequences
fall back to exploration. The immediate inverse-move skip removes adjacent
undo operations. These optimizations belong inside a search, not to global
class deduplication or inverse-wave coverage.

The CUDA tree excludes fixed-symbol supports and supports meeting three distinct
algebraic cycles, and permits odd-length-cycle-count increases zero or two. The
CPU prefix permits the three-distinct-cycle case when that increase is zero.
Therefore GPU exhaustion is not a lower-bound certificate. Native positional
triples are converted to symbol moves before Java certificate replay.

Final-root searches use full depth 11 without a time cutoff. Intermediate GPU
searches use the actual budget, with an optional configured time limit. Advisory
prefetch uses a positive time slice and is followed by the mandatory final scan.

Source anchors:
[GpuDeviceLayout](../emitter/src/main/java/br/unb/cic/td20/base/GpuDeviceLayout.java),
[jni_sorter.cu](../emitter/src/native/jni/jni_sorter.cu),
[slot_manager.cu](../emitter/src/native/backtracking/slot_manager.cu),
[sorting_search.cu](../emitter/src/native/backtracking/sorting_search.cu).

## 6. Reconstruction separates lookup, CPU, GPU and inverse workers

[ErikssonAvoidance](../emitter/src/main/java/br/unb/cic/td20/avoidance/ErikssonAvoidance.java)
owns the three wave transitions.
[ReconstructionPipeline](../emitter/src/main/java/br/unb/cic/td20/avoidance/ReconstructionPipeline.java)
classifies scanned after-two sources, and later processes the final roots.
The GPU worker never performs the inverse callback itself.

```mermaid
flowchart TB
    Set[("Complete DiskStateSet")] --> Read["Batched scan and multiGet replay"]
    Read -->|valid result at requested budget| Cached["Cached discharge"]
    Read -->|miss| CPU["CPU prefix workers"]
    CPU -->|found and persisted| CPUDone["CPU discharge"]
    CPU -->|miss| Q[("DiskStateQueue<br/>GPU handoff")]
    Q --> GPU["GPU dispatch workers"]
    GPU -->|found and persisted| GPUDone["GPU discharge"]
    GPU -->|miss| IQ[("Second DiskStateQueue<br/>Inverse handoff")]
    IQ --> Inv["Independent CPU inverse pool<br/>Residual callback"]
    Inv --> Dest["Next wave output<br/>or final unresolved counter"]
```

At the end, every pool joins and both queues drain. The pipeline checks:

```text
lookup hits + CPU discharges + GPU submissions = scanned source classes
GPU discharges + inverse submissions          = GPU submissions
completed inverse callbacks                   = inverse submissions
```

These equalities account for work classification. They do not count distinct
output classes; scratch persistence performs that deduplication.

## 7. The three inverse waves and their different consumers

```mermaid
flowchart TB
    E[("Residual endpoints<br/>16 symbols, budget 8")]
    E --> T["Wave 1: PrimitiveInverseReconstruction<br/>Restore 2; reverse third gain-2 move"]
    T --> A[("after-two<br/>18 symbols, budget 9")]
    A --> D["FULL_SEARCH: replay, CPU, GPU"]
    D -->|closed| Stop["Persist discharge; do not expand this source"]
    D -->|miss| S["Wave 2: reverse second move"]
    A -.->|RESIDUAL_EXPANSION source: expand only| S
    S -->|restore 2| B20[("after-one branch<br/>20 symbols, budget 10")]
    S -->|restore 3| B21[("after-one branch<br/>21 symbols, budget 10")]
    B20 --> F1["Wave 3: restore 1<br/>Reverse first gain-1 move"]
    B21 --> F0["Wave 3: restore 0<br/>Reverse first gain-0 move"]
    F1 --> Filter["Final family and immediate-closure filters"]
    F0 --> Filter
    Filter --> R[("roots: distinct elementary residuals<br/>21 symbols, budget 11")]
    R --> Final["Mandatory full root scan<br/>Replay, CPU, GPU"]
    Final -->|any miss| Fail["Unresolved: emission cannot complete"]
    Final -->|all closed| Done["Flush and publish ROOTS checkpoint"]
```

Every inverse generator covers both endpoints and every required support/gap
choice.
[PrimitiveInverseReconstruction](../emitter/src/main/java/br/unb/cic/td20/avoidance/PrimitiveInverseReconstruction.java)
uses worker-owned restored-domain arrays and emits borrowed raw arrays. Accepted
parents pass actual forward-gain and pair checks before persistence.

Fresh reconstruction uses `FULL_SEARCH` at after-two. A compatible completed
`RESIDUAL_EXPANSION` checkpoint identifies already selected source work and uses
`expandOnly()` there. These modes have different source meanings and cannot be
silently interchanged.

**Both modes expand every after-one source.** There is no budget-ten lookup or
sorting stage that removes sources before the final inverse wave. The combined
budget-ten set is drained and counted exactly with `countKeys()` before final
generation begins.

## 8. How the final wave avoids materializing elementary parents

The final-wave call is `DiskStateSet.addFinalInverseGenerated()`. It connects
three components in this order:

```mermaid
flowchart TB
    S["One budget-ten source"] --> Family["FinalInverseReconstruction<br/>InverseFamilyClosure derives masks per endpoint"]
    Family --> Cover{"Old support meets every mask?"}
    Cover -->|no| All["Close this support over all gaps<br/>No parent allocation"]
    Cover -->|yes| Gaps["Restore symbols in every gap<br/>Construct and forward-check parents"]
    Gaps --> Valid{"Valid fixed-point-free inverse parent?"}
    Valid -->|no| Reject["Reject candidate"]
    Valid -->|yes| Immediate["ImmediateRootClosure<br/>Check both endpoints and replay closing move"]
    Immediate -->|closes| Elementary["Elementary budget-eleven discharge"]
    Immediate -->|does not close| Key["PrimitiveCanonicalizer<br/>Pack canonical state key"]
    Key --> Disk[("DiskStateSet roots")]
```

The family test occurs **before gap expansion**. If no old support of the
required size can meet all masks, it closes the whole source-endpoint family.
Otherwise only the supports meeting every mask reach explicit parent generation.
Passing that filter is not evidence that a parent needs sorting: the subsequent
two-sided immediate test can still close it.

The following is a schematic mask graph, not a recorded source fixture. It
illustrates the software's support classification for a gain-one inverse,
whose old support has size two.

```mermaid
flowchart LR
    A["1"] --- B["2"] --- C["3"] --- D["4"]
    Keep["Old support 2,4<br/>Meets every edge: expand every gap"]
    Close["Old support 1,4<br/>Misses edge 2,3: close every gap"]
```

Both endpoint branches build masks from the actual source. Canonicalization
happens only after validity and individual immediate closure have been decided.
The resulting set is exactly the final residual inventory specified in the
paper; it is not an empirically selected list of difficult cases.

Source anchors:
[FinalInverseReconstruction](../emitter/src/main/java/br/unb/cic/td20/avoidance/FinalInverseReconstruction.java),
[InverseFamilyClosure](../emitter/src/main/java/br/unb/cic/td20/avoidance/InverseFamilyClosure.java),
[ImmediateRootClosure](../emitter/src/main/java/br/unb/cic/td20/avoidance/ImmediateRootClosure.java).

## 9. Output persistence, prefetch and completion barriers

Inverse callbacks pack keys into per-producer buffers. `DiskStateSet` routes
batches by deterministic hash to
[StateSetWriter](../emitter/src/main/java/br/unb/cic/td20/avoidance/StateSetWriter.java)
shards. Each writer owns one bounded queue and propagates worker failures.
Its persistence callback sorts and deduplicates the shard buffer, builds an SST, then
ingests it into the scratch RocksDB set. Identical keys across SSTs remain one
logical entry. SST writing bypasses WAL; ordinary RocksDB writes disable WAL.

```mermaid
%%{init: {"sequence": {"wrap": true, "width": 120, "actorMargin": 30}}}%%
sequenceDiagram
    participant G as Inverse workers
    participant W as State writer shards
    participant DB as DiskStateSet
    participant A as ErikssonAvoidance
    participant C as Certificate writer
    participant K as Eriksson<br/>Checkpoint
    G->>W: Buffered canonical state keys
    W->>DB: Sorted distinct SST batches and ingestion
    G-->>A: All source callbacks joined
    A->>C: Persistence barrier: flush upstream discharges
    C-->>A: Completed flush
    A->>DB: countKeys() on after-one
    DB->>W: Finish partial producer buffers and drain writers
    W-->>DB: All admitted output persisted
    DB->>DB: Count logical keys on one snapshot
    DB-->>A: Exact distinct source count
    A->>K: Publish AFTER_ONE stage and exact count
    A->>G: Begin final inverse wave over every source
```

This sequence shows the after-one boundary. At the final-root boundary, the
mandatory processing scan establishes the exact root count while checking
every root. Its workers join and certificates flush before the `ROOTS` checkpoint
is published. A failed writer or flush prevents advancing the checkpoint.

[RootPrefetch](../emitter/src/main/java/br/unb/cic/td20/avoidance/RootPrefetch.java)
overlaps final generation with early root lookups and searches. Generated root
key batches also enter a bounded advisory queue: multiGet replay first, CPU
misses next, and only remaining misses reach sliced GPU search. Queue pressure
can discard advisory work without blocking generation. Every distinct persisted
root is processed again by the mandatory scan, so discarded or suppressed
prefetch occurrences cannot omit obligations.

[AsyncCertificateWriter](../emitter/src/main/java/br/unb/cic/td20/certificate/AsyncCertificateWriter.java)
has a separate bounded queue for intrinsic results. Its write batches update
witness and obstruction families, WAL is disabled, and explicit `flush()` drains
admitted work and flushes the database. Background writes alone are not a
completed durability boundary. `enqueue()` also replays an incoming result
before encoding it, including results arriving through optional import paths.

[ErikssonCheckpoint](../emitter/src/main/java/br/unb/cic/td20/avoidance/ErikssonCheckpoint.java)
validates stage, mode, route, budgets, inventory kind and counts on resume.
An interrupted output wave is regenerated from its completed source boundary.
A supported older completed after-one checkpoint with a pending count is counted
and upgraded before final expansion. Old complete-root, incompatible-mode or
historical TD18 checkpoints cannot be relabeled as current filtered roots.

Generated/submitted/ingested counters can contain duplicate key occurrences.
Final expansion progress uses completed sources against the exact after-one
source total; the exact distinct output-root count is unknown until the mandatory
scan completes.

## 10. Optional result reuse and database maintenance

These components prepare or reuse intrinsic results around the main execution.
They do not replace the checker's regeneration.

```mermaid
flowchart TB
    Source[("Optional source witness database")]
    Pair["Generated endpoint pair"] --> Read["ReadOnlyWitnessSource<br/>Canonical lookup and replay at budget 8"]
    Source --> Read
    Read -->|valid within budget| Writer["AsyncCertificateWriter<br/>Checked result in TD20 database"]
    Read -->|miss| Search["Ordinary WitnessSearch"]
    After[("Complete original TD20 after-two set")]
    After --> Import["ImportTd18AfterTwo<br/>Read source results and replay every class"]
    Source --> Import
    Import -->|result within budget 9| Writer
    Import -->|missing or longer result| Residual[("Exact after-two residual set")]
    Residual --> Resume["Compatible RESIDUAL_EXPANSION input"]
```

Despite its name,
[ImportTd18AfterTwo](../emitter/src/main/java/br/unb/cic/td20/avoidance/ImportTd18AfterTwo.java)
requires the complete **TD20** after-two source inventory and compatible TD20
checkpoint. It imports replayed intrinsic witnesses from the source database,
not an archived TD18 reconstruction checkpoint. It checks source/imported/
residual accounting, flushes copied results, seals residuals, rechecks destination
coverage, then publishes its residual work and report.

[CertificateDatabaseMerger](../emitter/src/main/java/br/unb/cic/td20/certificate/CertificateDatabaseMerger.java)
opens one input database at a time, submits bounded record batches for replay,
and routes accepted results through `AsyncCertificateWriter`. Every duplicate
witness is replayed too. It joins and flushes each input before opening the next,
so unique-result counting does not depend on asynchronous write timing. Source
advisory markers are validated and omitted from the imported marker stream.

[CertificateDatabaseConsolidator](../emitter/src/main/java/br/unb/cic/td20/certificate/CertificateDatabaseConsolidator.java)
copies opaque key/value records from disjoint closed emission databases into
sorted SSTs, ingests and compacts them, and checks input/output inventories before
publishing the result. It preserves witness and marker families and explicitly
records `replay=none`. This differs from the merger's replay path. Both tools
produce database-maintenance reports; the independent checker supplies complete
proof verification.
