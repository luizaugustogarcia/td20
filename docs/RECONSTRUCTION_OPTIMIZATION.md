# Reconstruction optimization

The active proof route defines support-family closure and the filtered final
inventory. The emitter and checker implement this coverage independently.
See [the source paper](td20-reduction-count.tex).

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

## Adopted analytic coverage and checkpoint protocol

The paper proves that filtering produces exactly
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

Budget-ten generation covers the complete source inventory without sorting or
certificate pruning. State key bytes remain version
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

Validation requires the CPU suites, exact residual-multiset comparisons and
malformed or missing checkpoint rejection. Full independent proof regeneration
remains required.

## Benchmark commands

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
bash -ic 'TD20_JSHELL_OPTIONS="-R-Djava.library.path=emitter/target/inverse-benchmark -R-Dtd20.inverseBenchmarkSource=/path/to/completed-after-one" tools/td20 bench-inverse-gpu'
```

Use the allocated GPU's architecture explicitly on another machine. The
production native architecture defaults and library remain unchanged.
