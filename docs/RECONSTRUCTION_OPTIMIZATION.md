# Reconstruction optimization investigation — 19 November 2026

This report records the initial experiments and the subsequently authorized
production integration. [The active proof route](td20-reduction-count.tex) now
proves the support-family closure and specifies the filtered final inventory.
The emitter and independent checker implement it separately; runtime validation
and deployment status are recorded below and in [SESSION_HANDOFF.md](SESSION_HANDOFF.md).
No benchmark or source change proves the pending TD20 upper bound.

The operator requested restoration after reverting these changes. The
successful pre-revert patches and benchmark harness were recovered on
19 November 2026. Both fresh runs and residual resumes use final-family
filtering, and production flushes certificates before downstream checkpoints.
The later automatic budget-nine CPU-search draft is excluded. Old production
logs and obsolete outputs were deleted at the operator's request; the
completed source databases, certificate database and benchmark evidence remain.

The best direction is to avoid constructing and persisting roots which the
existing immediate-gain theorem already closes, then accelerate the remaining
work. A straightforward GPU port improves throughput, but the measured local
prototype is nowhere near a 100-fold end-to-end improvement.
The combined Java family-pruning prototype measured **164.9× and 91.5× faster**
wall time on the two final branches in a bounded, nonuniform real-source sample.
A subsequent production-path benchmark processed **24,554,710 sources**,
including decoding, disk persistence, exact output counting and reopening, at
**139,408/s and 241,993/s** for the two branches. These rates provide substantial
headroom above the one-to-two-day expansion requirement. Final root settlement
and independent proof checking remain outside that benchmark.
The detailed family derivation and further mathematical alternatives are in
[RECONSTRUCTION_ALTERNATIVES.md](RECONSTRUCTION_ALTERNATIVES.md).

## The actual workload and deadline

The persisted import audit records 5,032,950 after-two classes, of which
4,062,619 have replayed TD18 budget-nine certificates and 970,331 remain.
Expanding those residuals produced the completed, deduplicated set of
4,010,256,622 budget-ten classes. The expensive final expansion starts there.
The proposal preserves that successful reuse; it does not search the closed
after-two classes again or assume that the residuals have missing witnesses.

The historical production snapshot, whose log was subsequently deleted, at
03:25:53 UTC, records 87,176 completed sources, 632,519,272 generated key
occurrences, 488,112,128 SST-ingested occurrences, and an expansion ETA of
192 days. Its last interval processed 215.379 sources per second. The fixed
denominator requires these **sustained end-to-end source rates**:

| Expansion deadline | Required sources/second | Improvement over a 192-day run |
| --- | ---: | ---: |
| 48 hours | 23,207.5 | 96-fold |
| 24 hours | 46,415.0 | 192-fold |

Those deadlines exclude later final settlement and independent proof checking.
A two-day *complete proof computation* needs additional capacity for those stages.

The observed prefix has about 7,256 generated occurrences per source, but it is
not representative of the complete mixed-domain inventory: a 20-symbol source
has at most `2 * 20 * C(20,2) = 7,600` candidate gap/support combinations; a
21-symbol source has at most `2 * C(21,3) = 2,660`. These are candidate counts
before rejection, not distinct-root counts. Do not extrapolate a 29-trillion
root inventory from the first source range. Its distinct-root total is unknown.

## What is expensive now

`DiskStateSet.addInverseGenerated` invokes primitive Java inverse generation,
canonicalizes every accepted parent, allocates its version-three key and sends
it through bounded sharding, hash deduplication, sorting and RocksDB SST ingest.
Only afterward does advisory prefetch look for sorting certificates and closures.
The canonicalizer considers all 42 twisted images for a 21-symbol parent.

The GPU currently handles sorting fallback, not inverse generation. In the last
snapshot, CPU prefetch had closed 5,338,503 of 5,339,231 searched occurrences;
all 728 GPU fallthroughs had also closed. GPU slot occupancy was usually below
0.5%. This is a biased advisory sample with substantial duplicate suppression
and dropped occurrences; 99.9864% CPU closure is not a population estimate.
It does identify an opportunity to move closure ahead of canonicalization.

The old object-to-primitive gains are already in the production baseline.
Likewise, parallel source counting already scanned the complete inventory in
about 121 seconds. Neither improvement can be counted again toward this target.

## Mathematical audit and the Genetics manuscript

The examined inverse construction agrees with the active derivation: contract
fixed points first; restore every removed symbol in every cyclic gap; include
every old support choice; perform the inverse on both endpoints; reject parents
with fixed points; and replay the actual forward gain and both pair components.
The increasing order of restored labels only removes relabeling redundancy.
The anchored gap convention includes the gap after the final old symbol and
therefore every cyclic gap. These checks do not reveal a coverage defect.
They do not replace the pending full independent proof verification.

The problem is that this inverse cover includes many roots already discharged
by the first clause of the forward argument. Exhaustively generating this
overlap is sufficient for coverage but is not intrinsically necessary.

The supplied *Minimal Excluded Permutations in a Model of Genome Evolution*,
`/home/luiz/Temp/docs/Genetics 14 Oct 2026.pdf`, provides relevant supporting
ideas:

* Lemma 2.1 and Corollary 2.2 lift sorting paths through successor-adjacency
  contraction. This supports lifting a compact closure across a family, provided
  the relevant adjacency and target have actually been checked.
* Lemmas 2.8–2.10 describe the applicable algebraic 3-cycle and the cyclic-target
  move model. The manuscript uses right actions: its products must be translated
  before using the repository's successor-array formulas.
* Lemma 2.4 says adding one deleted entry costs at most one transposition.
  Together with TD(18) = 10, two arbitrary deletions from order 20 only give
  `10 + 2 = 12`. The desired budget is 11. Arbitrary deletion is not the same
  operation as contracting verified algebraic fixed points.
* Lemma 2.6 can supply additional closures for actual direct sums when the sum
  of the component bounds is at most eleven. Arbitrary disjoint algebraic
  cycles are not direct-sum blocks.
* The forbidden-pattern bound `b_k <= 3k+1` gives 34 at radius eleven, above
  the order-20 domain. The manuscript does not give a complete small forbidden
  basis that could replace reconstruction or prove this upper bound.

## Alternatives, in priority order

### 1. Certify easy inverse families before enumerating their members

Let `(omega,beta)` be a contracted source and let `rho = omega * beta`.
After restoring fixed symbols, an inverse on the current endpoint has
`omega_parent = omega * tau^-1`, `beta_parent = tau * beta`, and unchanged
`rho`, where `tau` is supported on the three selected symbols `S`. Set
`v = omega^-1`. At the untouched endpoint, `v_parent = tau * v`.

For every distinct triple `(x,y,z) = (x,v(x),v(v(x)))` in cyclic order in
`rho`, record the two-symbol mask `{y,z}`. If `S` misses this mask, both
images `v_parent(x)=y` and `v_parent(y)=z` survive. Thus that endpoint has an
applicable transposition creating at least two fixed symbols in the parent.
Every accepted 21-symbol parent in this family closes within
`1 + TD(18) = 11`. Inserting restored symbols leaves the cyclic order of old
symbols unchanged, so the condition holds over all their gap placements.

Consequently, a potentially hard support must meet every recorded mask.
These masks form edges of a subgraph of the algebraic cycles of `v`, with
maximum degree two. The old support must be a vertex cover of that graph:
at most two old symbols for gain one, or three for gain zero. If no such cover
exists, the entire endpoint expansion is closed. Otherwise enumerate covers
of size at most two/three, pad them to the required old support size, remove
duplicates, and expand only those supports through every required gap.
A bounded cover search branches on the two endpoints of an uncovered edge.
The same argument applies independently to the other inverse endpoint.

This is a sufficient closure condition. Surviving supports still require the
full ordinary inverse checks and may also close for other reasons. The test
does not use a budget-ten witness and does not discharge the source at budget
ten. Its conclusion concerns its final parents at budget eleven.

### 2. Test actual final parents before making canonical keys

For every remaining parent, scan consecutive algebraic triples on both
endpoints. On a fixed-point-free 21-symbol root, a gain of at least two leaves
at most nineteen cyclic symbols, or ordinary order eighteen. Replay that
transposition and apply the established diameter bound. Only parents not
covered by the closure need expensive canonicalization and explicit search.

This linear-time test is simpler than family pruning and supplies a useful
first implementation and correctness oracle. Unlike family pruning, it still
constructs every parent, so its maximum speedup is limited by inverse generation.
Neither its hit fraction nor the prefetch hit fraction alone is a speedup.

### 3. Fuse GPU generation, filtering and survivor compaction

Map a source batch and its endpoint/gap/support coordinates to independent
device work. Keep source arrays on the GPU, apply the family and actual-parent
tests, and canonicalize only survivors. Compact survivors before host transfer.
Use bounded batches with explicit overflow rejection/retry, double buffering,
and pinned transfer buffers. Preserve exact packed keys and ordinary Java
persistence; canonical-key equality permits reuse of existing witnesses.

A port which returns every key merely moves the bottleneck to PCIe, Java
allocation and disk. Current-output persistence must also be improved if the
existing complete-root inventory is retained. Hashes alone cannot suppress
proof obligations: exact keys must resolve collisions, and a Bloom filter can
only avoid unnecessary lookups, never establish that a result exists.

### 4. Reduce duplicate expansion and persistence costs

For supports still requiring expansion, consider exact bounded local key
deduplication before the writer queue; packed fixed-width arrays and radix
sorting instead of per-key `ByteBuffer`/hash-node allocation; and large
nonoverlapping output partitions followed by deterministic merging. Measure
duplicate rates at each boundary before selecting buffer sizes. These changes
preserve all class obligations but do not eliminate the large raw fanout.

A canonical construction-path rule could assign each root to one eligible
source, avoiding repeated global writes. It needs a proof that every root has
an owner in the actual residual-derived source set, exact membership queries,
and transport of supports through canonicalization. It is a later option, not
an assumption that the lexicographically first unrestricted child is present.

### 5. Scale the reduced workload over the fleet

Once measured work per source is lower, partition the retained budget-ten
source into fine-grained deterministic ranges with a shared dynamic queue.
Use separate budgets for source scanning, GPU work and writers and profile each
machine's actual allocation. Server count multiplies sustained throughput only
until storage or coordinator transfer saturates. More existing sorting slots
alone cannot accelerate Java inverse generation.

## Adopted analytic coverage and checkpoint protocol

The user authorized production integration and restart after further validation
on 19 November 2026. The paper now proves that filtering produces exactly
`R = P \ D11`, where `P` is the full final inverse cover and `D11` consists
of roots with an immediate gain-at-least-two move on either endpoint.

The initial proposal of storing a separate certificate for every source family
is unnecessary. These are analytic elementary closures, as in initial root
generation: the checker independently regenerates every source, constructs its
witness graph, checks the family condition and handles both inverse branches
and endpoints. The emitter uses a bounded cover search; the checker independently
uses path/cycle component cover formulas and direct block exchanges. Every
explicit inverse parent retains actual forward replay, and every residual root
requires a database witness at budget eleven. No stored obstruction marker,
family list or emitter checkpoint can omit checker work.

The complete budget-ten source inventory and TD18 reuse are unchanged. There
is no budget-ten sorting or certificate pruning. State key bytes remain version
three, so existing sorting witnesses remain reusable. Filtered final `ROOTS`
checkpoints use `td-eriksson-reconstruction-v4`, retain their original
`FULL_SEARCH` or `RESIDUAL_EXPANSION` mode, and add
`rootInventory=ELEMENTARY_RESIDUALS`. Both modes use the same filtered final
wave without budget-ten sorting or certificate pruning. Their
`roots` count is the exact cardinality of `R`, never the full `P` inventory.
Old completed after-one inputs retain their original meaning; old all-parent
ROOTS checkpoints are explicitly distinct. Cleanup and restart tools validate
the new format.

Checker coverage reports use `inverse-coverage-v3.txt` with the inventory kind,
complete source and support/gap accounting, replayed-parent and immediate-closure
counts, exact distinct residual roots and unresolved count. Interrupted work,
missing source classes and missing residual witnesses prevent completion.
Candidate occurrence counts do not supply a distinct count of closed roots.

The required CPU suites, exact residual-multiset comparisons, malformed/missing
checkpoint rejection and longer persisted-source benchmarks precede production
restart. Full independent proof regeneration remains a separate required run.

## Measurements and reproduction

### Straightforward CUDA port

An isolated CUDA prototype implements both inverse endpoints, all gains 0..3,
exact forward replay and byte-compatible canonical keys. It uses deterministic
output slots for every possible support, with a checked 256 MiB batch ceiling,
so an overflow cannot silently truncate output. The CMake target is optional
and disabled by default. This prototype does not filter or persist roots.

The local RTX 4070 Laptop GPU (8 GiB) was compared with all 22 Java CPU workers.
Each row uses a batch of 64 sources and the median of three measured runs
after two warmups. The 20/21-symbol sources are distinct deterministic seeks
into the real after-one database; these are nonuniform samples.

| Source symbols / gain | Java milliseconds | CUDA milliseconds | Speedup | CUDA outputs/second |
| --- | ---: | ---: | ---: | ---: |
| 18 / 2, synthetic | 69.34 | 30.86 | 2.25× | 12.77 million |
| 18 / 3, synthetic | 28.12 | 14.93 | 1.88× | 9.77 million |
| 20 / 1, persisted | 87.44 | 58.72 | 1.49× | 7.94 million |
| 21 / 0, persisted | 44.40 | 26.46 | 1.68× | 5.71 million |

CUDA time includes native allocation, transfer, enumeration, replay,
canonicalization and returning Java bytes. Java time also includes inexpensive
digest accumulation; GPU digest validation is outside its timed interval.
Neither includes RocksDB writes. The native canonicalizer has a 1,512-byte
per-thread stack frame. These exploratory measurements do not establish the
fraction of production time attributable to any one stage.

Exact complete output multisets agreed for 62 source/gain fixtures: 46
small/synthetic fixtures and 16 benchmark-source fixtures, covering both
endpoints and all gains. Every timed/warmup batch also matched counts and key
digests. The corrected distinct-source log is
`local-runtime/inverse-gpu-benchmark-distinct.log`; the earlier log permitted
duplicate sampled sources and is superseded for timing comparisons.

### Early closure and support-family samples

The larger Java sample uses 128 deterministic key seeks per domain and up to
16 distinct neighbors per seek. It contains 1,920 sources of size 20 and
1,904 of size 21. This is deliberately bounded, **not a uniform random sample**
or a population count. It includes 18,472,861 accepted inverse occurrences.
Only 262 occurrences fail the two-sided immediate-gain test; these are not
claimed to be 262 distinct classes. Every surviving occurrence still needs
canonicalization and certificate search.

| Source symbols | Accepted inverse occurrences | Immediate-test survivors | Candidate supports and gaps left by family masks |
| --- | ---: | ---: | ---: |
| 20 | 13,977,480 | 102 | 369,760 / 14,592,000 |
| 21 | 4,495,381 | 160 | 171,960 / 5,064,640 |

The last column counts candidate slots before ordinary inverse rejection,
not accepted or distinct roots. Family masks remove 97.47% and 96.60% of
candidate work respectively in these samples. A first implementation which
still constructs every parent but canonicalizes only immediate-test survivors
uses about twelve times less CPU. Omitting only sources whose *both* endpoint
families close reduces CPU by about 16–21 times. Neither implementation yet
uses all the per-support elimination shown in the table. Those intermediate
timings shared the machine with validation, so they are not deployment forecasts.
The log is `/tmp/td20-reconstruction-alternatives-expanded-benchmark.log`.

The final experiment implements the stronger support filter in
`FamilyPrunedInverseReconstruction`: it computes unresolved old supports once
per endpoint, then visits every gap only for those supports. Remaining parents
undergo the original exact gain and pair replay, followed by the immediate
closure test; only its survivors receive canonical keys. The final benchmark
ran after both Maven suites had finished, using the same larger sample and
all 22 CPU workers, with two warmups and three measured repetitions:

| Source symbols / inverse gain | All-key baseline seconds | Family-pruned seconds | Wall speedup | Family-pruned sources/second |
| --- | ---: | ---: | ---: | ---: |
| 20 / 1 | 3.080063 | 0.018680 | 164.9× | 102,786 |
| 21 / 0 | 0.974636 | 0.010655 | 91.5× | 178,700 |

The pruned runs construct 354,307 and 146,714 accepted parents respectively,
and preserve all 102 and 160 immediate-test survivor occurrences and their
canonical-key digests. Summed worker CPU times improve by 378.8× and 299.9×,
but the wall ratios are the relevant measured execution comparisons. The
short optimized timings include thread-pool overhead and have limited precision.
No GPU is used. The full log is `local-runtime/reconstruction-optimized-benchmark.log`.

Holding those compute-only rates over all 4.01 billion sources would imply
roughly 6–11 hours. This is an extrapolation, **not an ETA**: source sampling is
nonuniform, branch proportions and hard-tail behavior are not yet measured,
and source decoding, persistence, final searches and independent regeneration
were outside that initial experiment. The
measured rates provide useful headroom above the 23,208/s two-day requirement;
they justify implementing the protocol rather than resuming the old expansion.

The immediate predicate was compared with exhaustive arbitrary-support tests
on all valid fixed-point-free normalized pairs through eight symbols. The
family predicate was checked on every such source of three through six symbols,
both inverse endpoints, both final gains, all supports and gaps: 6,282 valid
inverse parents. Every predicted family closure was checked by an independent
arbitrary-support oracle and actual pair replay. The larger sample additionally
replayed up to eight successful closures per source, 30,592 replays in total.
These are implementation tests, not full-domain proof artifacts.
The support-pruned enumerator additionally preserves exact per-class survivor
multiplicities against full inverse enumeration for every valid contracted
normalized source through seven symbols and both final gains.

Initial experimental validation used base revision `552b5a9` plus these uncommitted experimental
changes. `mvn -pl emitter -am test` passed 296 tests with zero failures/errors
and 16 configured CUDA skips; `mvn -pl checker -am test` passed all 105 tests.
The initial sandboxed emitter run could not create JShell's loopback connection
in the existing fleet integration test; the complete rerun with local socket
access passed. Logs are `/tmp/td20-optimization-emitter-tests-host.log` and
`/tmp/td20-optimization-checker-tests.log`. CUDA multiset validation is the
separate configured experiment reported above. Launcher syntax, prepared
JShell invocations and `git diff --check` also passed.

### Longer production-path benchmark

The confirmation run at 04:05:26–04:07:44 UTC on 19 November used the production
`DiskStateSet.addFinalInverseGenerated` path, 22 automatically detected workers,
and bounded 512-source tasks. It scanned 352 deterministic disjoint key windows
per domain, each limited to 65,536 sources. Some windows are empty or shorter;
the selection is nonuniform and is not a random population sample.

| Source symbols | Completed sources | Wall seconds | Sources/s | Allocated CPU use | Residual occurrences | Distinct residual roots |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 11,994,613 | 86.040 | 139,408 | 97.90% | 411,589 | 288,006 |
| 21 | 12,560,097 | 51.903 | 241,993 | 96.14% | 504,652 | 454,419 |

Timing includes source reads and decoding, production family filtering and
actual-parent replay, canonicalization, bounded disk persistence, writer drain,
exact unique-key counting, and reopened-inventory validation. Full unfiltered
inverse enumeration also agreed with production on all 272 selected oracle
sources and their 70 residual occurrences. Every scheduled window completed;
the report rejects incomplete source or persisted inventories.

The log is `local-runtime/reconstruction-long-benchmark-20261119-v2.log`.
The versioned report, window inventory and sealed residual databases are under
`local-runtime/work/reconstruction-long-benchmark-20261119-v2/`.
The preceding smaller run covered 6,161,909 sources; reducing scan task size
removed its coarse-window worker tail. No source or certificate database was
modified by either benchmark.

Holding the two measured branch rates over all 4,010,256,622 sources would take
approximately **4.6–8.0 hours for expansion**. This is a conditional extrapolation,
not a full-run ETA: unsampled regions, certificate lookup, CPU/GPU settlement of
the residual roots, and independent proof regeneration can change total runtime.
The remaining roots fail immediate closure, so the old unfiltered prefetch
success rates do not measure their sorting cost.

Production integration validation passed both complete required CPU suites:
emitter **299 tests, zero failures/errors, 16 configured CUDA skips**; checker
**110 tests, zero failures/errors/skips**. Emitter validation is logged in
`/tmp/td20-family-production-emitter-tests.log`; checker validation is logged in
`/tmp/td20-family-production-checker-tests.log`. The paper compiled successfully,
and the checkpoint-cleanup tests passed all 12 cases. The configured production
CUDA sorting smoke tests also passed both cases; log:
`/tmp/td20-family-production-gpu-smoke.log`.

### Commands

All generated data and logs remain ignored. Use the normal prepared JShell
classpath and automatic CPU detection:

```bash
bash -ic 'tools/td20 build'
bash -ic 'tools/td20 bench-reconstruction'
bash -ic 'TD20_JSHELL_OPTIONS="-R-Dtd20.bench.seekRanges=128 -R-Dtd20.bench.samplesPerSeek=16" tools/td20 bench-reconstruction'
```

Override `td20.bench.afterOne` with a completed source database when necessary.
The benchmark opens it read-only and never writes proof results. For the local
SM 89 GPU experiment:

```bash
bash -ic 'cmake -S emitter/src/native -B emitter/target/inverse-benchmark -DTD20_CUDA_ARCHITECTURES=89-real -DTD20_BUILD_INVERSE_BENCHMARK=ON -DCMAKE_BUILD_TYPE=Release'
bash -ic 'cmake --build emitter/target/inverse-benchmark --target td20_inverse_benchmark --parallel "$(nproc)"'
bash -ic 'TD20_JSHELL_OPTIONS="-R-Djava.library.path=emitter/target/inverse-benchmark -R-Dtd20.inverseBenchmarkSource=local-runtime/work/emitter-20261118T024413Z-706373/after-one" tools/td20 bench-inverse-gpu'
```

Use the allocated GPU's architecture explicitly on another machine. The
production native architecture defaults and library remain unchanged.
