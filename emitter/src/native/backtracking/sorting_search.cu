#include "backtracking/sorting_search.cuh"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <cmath>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <utility>
#include <vector>

#include "backtracking/slot_manager.cuh"
#include "backtracking/cycle_metadata.cuh"
#include "core/cuda_error.cuh"
#include "core/cuda_memory.cuh"

namespace {
    constexpr int WARP_SIZE = 32;

    // Tables for sizes 3..n are concatenated; sum_{k=3}^{n-1} C(k,3)=C(n,4).
    __host__ __device__ __forceinline__ int triple_table_offset(const int n) {
        return n < 4 ? 0 : n * (n - 1) * (n - 2) * (n - 3) / 24;
    }

    struct alignas(4) BacktrackingNode {
        uint8_t depth;
        uint8_t oddCycles;
        uint8_t fixedPoints;
        PackedMove moves[MAX_DEPTH];
        uint8_t omega[MAX_N];
        uint8_t betaStart;  // first element of beta cycle (reconstructed via rho invariant)
    };

    constexpr int MEMO_CAPACITY = 1 << 19;
    struct MemoEntry {
        int lock;
        int depth;
        unsigned long long low;
        unsigned long long high;
    };
    static_assert(MAX_N <= 24, "Search state keys use two 60-bit symbol fields");
    static_assert(sizeof(MemoEntry) == 24, "Search cache memory accounting changed");

    struct BacktrackingQueue {
        int head;
        int tail;
        int size;
        int lock;
        int capacity;
        BacktrackingNode *nodes;
    };

    struct BacktrackingResult {
        int found;
        int solutionDepth;
        int solutionKind;
        int overflowed;
        int timedOut;
        PackedMove solutionMoves[MAX_DEPTH];
        alignas(8) unsigned long long outstandingWork;
        unsigned long long startedAtNanos;
    };

    struct DeviceSearchContext {
        const short *tripleFirst;
        const short *tripleSecond;
        const short *tripleThird;
        int tripleCount;
        const short *omega0;
        const short *beta0;
        int n;
        int maxDepth; // Full budget for diameter closures and admissible pruning.
        int searchDepth; // Maximum number of moves actually explored.
        int knownDiameters[MAX_N + 1];
        int maxAllOddClosureRank; // Optimistic maximum rank closing with zero slack.

        int queueCount;
        BacktrackingQueue *queues;
        BacktrackingQueue *overflow;

        int *foundFlag;
        int *solutionDepth;
        int *solutionKind;
        PackedMove *solutionMoves;
        int *overflowFlag;
        int *timeoutFlag;
        unsigned long long *outstandingWork;
        unsigned long long *startedAtNanos;
        unsigned long long maximumNanos;

        MemoEntry *memo;
        int reverseTripleOrder;
        int allowDiameterClosure;
        uint8_t rhoInv[MAX_N];  // precomputed rho^{-1} for beta reconstruction
    };

    struct SlotBuffers {
        int device = -1;
        int queueCount = 0;
        int localCapacity = 0;
        int overflowCapacity = 0;
        int cachedTriplesN = -1;
        DeviceAsyncUniquePtr<BacktrackingQueue> queues{};
        DeviceAsyncUniquePtr<BacktrackingQueue> overflow{};
        DeviceAsyncUniquePtr<BacktrackingNode> nodes{};
        DeviceAsyncUniquePtr<BacktrackingNode> overflowNodes{};
        DeviceAsyncUniquePtr<short> tripleFirst{};
        DeviceAsyncUniquePtr<short> tripleSecond{};
        DeviceAsyncUniquePtr<short> tripleThird{};
        DeviceAsyncUniquePtr<short> inputVectors{};
        DeviceAsyncUniquePtr<BacktrackingResult> result{};
        DeviceAsyncUniquePtr<MemoEntry> memo{};
        HostPinnedUniquePtr<BacktrackingResult> hostResult{};
    };

    struct SlotBufferView {
        BacktrackingQueue *queues = nullptr;
        BacktrackingQueue *overflow = nullptr;
        BacktrackingNode *nodes = nullptr;
        BacktrackingNode *overflowNodes = nullptr;
        short *tripleFirst = nullptr;
        short *tripleSecond = nullptr;
        short *tripleThird = nullptr;
        short *inputVectors = nullptr;
        BacktrackingResult *result = nullptr;
        BacktrackingResult *hostResult = nullptr;
        bool triplesNeedUpload = false;
    };

    std::mutex gSlotsMutex;
    std::vector<SlotBuffers> gSlots;

    void releaseSlotBuffers(SlotBuffers &buffers) {
        buffers.queues.reset();
        buffers.overflow.reset();
        buffers.nodes.reset();
        buffers.overflowNodes.reset();
        buffers.tripleFirst.reset();
        buffers.tripleSecond.reset();
        buffers.tripleThird.reset();
        buffers.inputVectors.reset();
        buffers.result.reset();
        buffers.hostResult.reset();
        buffers.memo.reset();
        buffers.queueCount = 0;
        buffers.localCapacity = 0;
        buffers.overflowCapacity = 0;
        buffers.cachedTriplesN = -1;
    }

    SlotBufferView viewSlotBuffers(const SlotBuffers &buffers, const int n) noexcept {
        return SlotBufferView{
                buffers.queues.get(),
                buffers.overflow.get(),
                buffers.nodes.get(),
                buffers.overflowNodes.get(),
                buffers.tripleFirst.get(),
                buffers.tripleSecond.get(),
                buffers.tripleThird.get(),
                buffers.inputVectors.get(),
                buffers.result.get(),
                buffers.hostResult.get(),
                buffers.cachedTriplesN != n
        };
    }

    SlotBufferView getSlotBufferView(const int slot, const int n) {
        if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) {
            throw std::invalid_argument("invalid slot");
        }
        return viewSlotBuffers(gSlots[slot], n);
    }

    void markSlotTriplesUploaded(const int slot, const int device, const int n) {
        if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) return;
        auto &buffers = gSlots[slot];
        if (buffers.device == device && buffers.tripleFirst != nullptr) {
            buffers.cachedTriplesN = n;
        }
    }

    // ─── Device helper functions ─────────────────────────────────────────────────

    // Explicit PTX mapping for device-scoped relaxed atomic loads.
    // These polls need no acquire: queue locks protect nodes and stream
    // completion publishes the result payload to the host. Unlike atomicAdd
    // with zero, a load does not serialize a write on every polling warp.
    __device__ __forceinline__ int load_relaxed(const int *address) {
        int value;
#if __CUDA_ARCH__ >= 700
        asm volatile("ld.relaxed.gpu.b32 %0, [%1];" : "=r"(value) : "l"(address) : "memory");
#else
        asm volatile("ld.volatile.b32 %0, [%1];" : "=r"(value) : "l"(address) : "memory");
#endif
        return value;
    }

    __device__ __forceinline__ unsigned long long load_relaxed(const unsigned long long *address) {
        unsigned long long value;
#if __CUDA_ARCH__ >= 700
        asm volatile("ld.relaxed.gpu.b64 %0, [%1];" : "=l"(value) : "l"(address) : "memory");
#else
        asm volatile("ld.volatile.b64 %0, [%1];" : "=l"(value) : "l"(address) : "memory");
#endif
        return value;
    }

    __device__ __forceinline__ void acquire_lock(int *lock) {
        while (atomicCAS(lock, 0, 1) != 0);
    }

    __device__ __forceinline__ void release_lock(int *lock) {
        atomicExch(lock, 0);
    }

    __device__ __forceinline__ bool owner_pop(BacktrackingQueue &q, BacktrackingNode &node) {
        acquire_lock(&q.lock);
        if (q.size <= 0) { release_lock(&q.lock); return false; }
        q.tail = q.tail == 0 ? q.capacity - 1 : q.tail - 1;
        node = q.nodes[q.tail];
        q.size--;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    __device__ __forceinline__ bool stealer_pop(BacktrackingQueue &q, BacktrackingNode &node) {
        if (atomicAdd(&q.size, 0) <= 0) return false;
        acquire_lock(&q.lock);
        if (q.size <= 0) { release_lock(&q.lock); return false; }
        node = q.nodes[q.head];
        q.head = q.head + 1 == q.capacity ? 0 : q.head + 1;
        q.size--;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    __device__ __forceinline__ bool donate_push(BacktrackingQueue &q, const BacktrackingNode &node) {
        if (atomicAdd(&q.size, 0) >= q.capacity) return false;
        acquire_lock(&q.lock);
        if (q.size >= q.capacity) { release_lock(&q.lock); return false; }
        q.head = q.head == 0 ? q.capacity - 1 : q.head - 1;
        q.nodes[q.head] = node;
        q.size++;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    // Within a search rho is fixed, so omega determines beta as a cycle.
    // A cyclic rotation of beta does not change legal supports. An occurrence
    // at no greater depth has at least as much budget and exploration depth.
    // The preceding inverse edge leads to its already visited parent; omitting
    // that edge cannot add a new reachable state. Compare every key bit: hash
    // collisions or a full bounded probe always fall back to exploration.
    // Volatile fields and fenced lock publication keep entries coherent.
    // Entries reset between searches.
    __device__ bool memo_dominated(const DeviceSearchContext &ctx, const BacktrackingNode &node) {
        unsigned long long low = 0;
        unsigned long long high = 0;
        for (int i = 0; i < ctx.n; ++i) {
            if (i < 12) low |= static_cast<unsigned long long>(node.omega[i]) << (5 * i);
            else high |= static_cast<unsigned long long>(node.omega[i]) << (5 * (i - 12));
        }
        unsigned long long hash = low ^ (static_cast<unsigned long long>(high) * 0x9e3779b97f4a7c15ULL);
        hash ^= hash >> 30; hash *= 0xbf58476d1ce4e5b9ULL;
        hash ^= hash >> 27; hash *= 0x94d049bb133111ebULL; hash ^= hash >> 31;
        for (int probe = 0; probe < 8; ++probe) {
            volatile MemoEntry &entry = ctx.memo[(hash + probe) & (MEMO_CAPACITY - 1)];
            auto *lock = const_cast<int *>(&entry.lock);
            acquire_lock(lock);
            bool matched = false, dominated = false;
            if (entry.depth == 0) {
                entry.low = low; entry.high = high; entry.depth = node.depth;
                matched = true;
            } else if (entry.low == low && entry.high == high) {
                dominated = entry.depth <= node.depth;
                if (!dominated) entry.depth = node.depth;
                matched = true;
            }
            __threadfence();
            release_lock(lock);
            if (matched) return dominated;
        }
        return false;
    }

    // The device-wide nanosecond timer has one time base across SMs, unlike clock64().
    __device__ __forceinline__ unsigned long long device_time_nanos() {
        unsigned long long value;
        asm volatile("mov.u64 %0, %%globaltimer;" : "=l"(value));
        return value;
    }

    __device__ __forceinline__ bool warp_should_stop(const DeviceSearchContext &ctx) {
        int shouldStop = 0;
        if (threadIdx.x == 0) {
            shouldStop = (load_relaxed(ctx.foundFlag) != 0
                        || load_relaxed(ctx.overflowFlag) != 0) ? 1 : 0;
            if (!shouldStop && ctx.maximumNanos != 0) {
                shouldStop = load_relaxed(ctx.timeoutFlag) != 0;
                if (!shouldStop && device_time_nanos() - *ctx.startedAtNanos >= ctx.maximumNanos) {
                    atomicExch(ctx.timeoutFlag, 1);
                    shouldStop = 1;
                }
            }
        }
        return __shfl_sync(0xFFFFFFFF, shouldStop, 0) != 0;
    }

    // ─── Odd-cycle counting ─────────────────────────────────────────────────────

    __host__ __device__ __forceinline__ int count_odd_cycles(const uint8_t *omega, const int n) {
        uint64_t visited = 0;
        int count = 0;
        for (int s = 0; s < n; ++s) {
            if (visited & (1ULL << s)) continue;
            int size = 0;
            int cur = s;
            do {
                visited |= (1ULL << cur);
                size++;
                cur = omega[cur];
            } while (cur != s);
            if (size & 1) count++;
        }
        return count;
    }

    __device__ __forceinline__ bool has_even_length_cycle(const uint8_t *omega, const int n) {
        uint64_t visited = 0;
        for (int first = 0; first < n; ++first) {
            if (visited & (1ULL << first)) continue;
            int current = first, length = 0;
            do {
                visited |= 1ULL << current;
                ++length;
                current = omega[current];
            } while (current != first);
            if ((length & 1) == 0) return true;
        }
        return false;
    }

    // Incremental odd-cycle delta when applying 3-cycle (a b c) to omega.
    // The move sets: childOmega[a]=omega[c], childOmega[b]=omega[a], childOmega[c]=omega[b].
    //
    // Returns the delta in odd-cycle count (childOddCycles - parentOddCycles),
    // or INT_MIN if a, b, c span 3 different cycles (move should be skipped).
    //
    // Cases:
    //   All in same cycle, order a→...→b→...→c→...→a: splits into 3 cycles of
    //     sizes (d_ab, d_bc, d_ca). Delta depends on parity of each part.
    //   All in same cycle, order a→...→c→...→b→...→a: cycle stays intact. Delta = 0.
    //   Two in one cycle, one in another: 2 cycles remain 2 cycles with new sizes.
    //   Three different cycles: skipped (returns INT_MIN).
    __device__ __forceinline__ int delta_odd_cycles(const uint8_t *omega,
                                                     const uint8_t a,
                                                     const uint8_t b,
                                                     const uint8_t c) {
        // Trace from a following omega until we hit one of {a, b, c}.
        // This gives us the segment length and tells us which cycle(s) they share.
        int d_a = 0; // steps from a (exclusive) to first hit of {a,b,c}
        uint8_t target_a; // which of {a,b,c} we hit
        {
            uint8_t cur = omega[a];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_a++; }
            d_a++; // count the final step to the target
            target_a = cur;
        }

        int d_b = 0;
        uint8_t target_b;
        {
            uint8_t cur = omega[b];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_b++; }
            d_b++;
            target_b = cur;
        }

        int d_c = 0;
        uint8_t target_c;
        {
            uint8_t cur = omega[c];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_c++; }
            d_c++;
            target_c = cur;
        }

        // Determine the case based on targets.
        // d_a = distance from a to target_a (number of omega steps, a excluded, target included)
        // The "full distance" including a itself as a node: segment has d_a elements after a.

        if (target_a == a && target_b == b && target_c == c) {
            // All three loop back to themselves → 3 different cycles. Skip move.
            return INT_MIN;
        }

        if (target_a == b && target_b == c && target_c == a) {
            // All in same cycle, order a→...→b→...→c→...→a
            // Splits into 3 cycles of sizes d_a, d_b, d_c (each segment becomes a cycle)
            // The original cycle size = d_a + d_b + d_c
            const int L = d_a + d_b + d_c;
            const int oldOdd = (L & 1) ? 1 : 0;
            const int newOdd = (d_a & 1) + (d_b & 1) + (d_c & 1);
            return newOdd - oldOdd;
        }

        if (target_a == c && target_b == a && target_c == b) {
            // All in same cycle, order a→...→c→...→b→...→a (reverse order)
            // Cycle stays intact, same size. No change.
            return 0;
        }

        // Two in one cycle, one in another.
        // Determine which two share a cycle and compute the delta.
        if (target_a == b && target_b == a) {
            // a and b in same cycle (size L1 = d_a + d_b), c alone (size L2 = d_c)
            // New sizes: d_a and (d_b + d_c)
            const int L1 = d_a + d_b;
            const int L2 = d_c;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_a & 1) + ((d_b + d_c) & 1);
            return newOdd - oldOdd;
        }

        if (target_b == c && target_c == b) {
            // b and c in same cycle (size L1 = d_b + d_c), a alone (size L2 = d_a)
            // New sizes: d_b and (d_c + d_a)
            const int L1 = d_b + d_c;
            const int L2 = d_a;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_b & 1) + ((d_c + d_a) & 1);
            return newOdd - oldOdd;
        }

        if (target_a == c && target_c == a) {
            // a and c in same cycle (size L1 = d_a + d_c), b alone (size L2 = d_b)
            // New sizes: d_c and (d_a + d_b)
            const int L1 = d_a + d_c;
            const int L2 = d_b;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_c & 1) + ((d_a + d_b) & 1);
            return newOdd - oldOdd;
        }

        // Should not reach here for valid permutations, but guard:
        return INT_MIN;
    }

    // ─── Pruning logic ───────────────────────────────────────────────────────────

    __host__ __device__ __forceinline__ int count_fixed_points(const uint8_t *omega, const int n) {
        int count = 0;
        for (int s = 0; s < n; ++s) count += omega[s] == s;
        return count;
    }

    __device__ __forceinline__ int solution_kind(const DeviceSearchContext &ctx,
                                                  const int oddCycles,
                                                  const int fixedPoints,
                                                  const int depth) {
        if (oddCycles == ctx.n) return 1;
        const int diameter = ctx.knownDiameters[ctx.n - fixedPoints];
        if (ctx.allowDiameterClosure && diameter != INT_MAX && diameter <= ctx.maxDepth - depth) return 2;
        return 0;
    }

    __device__ __forceinline__ bool can_still_reach(const DeviceSearchContext &ctx,
                                                    const int oddCycles,
                                                    const int depth) {
        // Every permitted transposition increases the odd-length-cycle count by at most two.
        return depth + (ctx.n - oddCycles + 1) / 2 <= ctx.maxDepth;
    }

    __device__ __forceinline__ bool contains_fixed_symbol(const uint8_t *omega,
                                                          const uint8_t a,
                                                          const uint8_t b,
                                                          const uint8_t c) {
        return omega[a] == a || omega[b] == b || omega[c] == c;
    }

    // ─── Initialization kernel ───────────────────────────────────────────────────

    __global__ void initialize_queues_kernel(const DeviceSearchContext ctx,
                                             const short *tripleFirst,
                                             const short *tripleSecond,
                                             const short *tripleThird,
                                             const int tripleCount,
                                             BacktrackingQueue *queues,
                                             const int queueCount,
                                             BacktrackingNode *nodes,
                                             const int localCapacity,
                                             BacktrackingQueue *overflow,
                                             BacktrackingNode *overflowNodes,
                                             const int overflowCapacity,
                                             int *overflowFlag,
                                             unsigned long long *outstandingWork,
                                             const short *omega0,
                                             const short *beta0,
                                             const int n,
                                             const int initialOddCycles,
                                             const int initialFixedPoints,
                                             const int reverseTripleOrder) {
        // The following search kernel runs in the same stream, after this write.
        // Queue waiting on the host does not consume this per-search allowance.
        if (blockIdx.x == 0 && threadIdx.x == 0 && ctx.maximumNanos != 0)
            *ctx.startedAtNanos = device_time_nanos();
        const int queueIndex = static_cast<int>(blockIdx.x * blockDim.x + threadIdx.x);
        if (queueIndex < queueCount) {
            BacktrackingQueue queue{};
            queue.head = 0;
            queue.lock = 0;
            queue.capacity = localCapacity;
            queue.nodes = nodes + static_cast<size_t>(queueIndex) * localCapacity;

            int rootCount = 0;
            if (queueIndex < tripleCount) {
                const int queueRoots = 1 + (tripleCount - 1 - queueIndex) / queueCount;
                if (queueRoots > localCapacity) {
                    atomicExch(overflowFlag, 1);
                }
                const int rootLimit = min(localCapacity, queueRoots);
                for (int rootIndex = 0; rootIndex < rootLimit; ++rootIndex) {
                    if (load_relaxed(ctx.foundFlag) != 0) break;
                    const int fwdTripleIndex = queueIndex + rootIndex * queueCount;
                    const int tripleIndex = reverseTripleOrder
                        ? (tripleCount - 1 - fwdTripleIndex)
                        : fwdTripleIndex;
                    const short moveFirst = tripleFirst[tripleIndex];
                    const short moveSecond = tripleSecond[tripleIndex];
                    const short moveThird = tripleThird[tripleIndex];
                    BacktrackingNode node{};
                    node.depth = 1;
                    node.moves[0] = pack_move(moveFirst, moveSecond, moveThird);

                    const short a = beta0[moveFirst];
                    const short b = beta0[moveSecond];
                    const short c = beta0[moveThird];
                    for (int p = 0; p < n; ++p) {
                        node.omega[p] = static_cast<uint8_t>(omega0[p]);
                    }

                    // Root moves must obey the same restrictions as descendants.
                    // In particular, do not seed the search with a move that
                    // merges three distinct cycles.
                    if (contains_fixed_symbol(node.omega, a, b, c)) continue;
                    const int delta = delta_odd_cycles(node.omega, a, b, c);
                    if (delta != 0 && delta != 2) continue;

                    node.omega[a] = static_cast<uint8_t>(omega0[c]);
                    node.omega[b] = static_cast<uint8_t>(omega0[a]);
                    node.omega[c] = static_cast<uint8_t>(omega0[b]);

                    node.oddCycles = static_cast<uint8_t>(initialOddCycles + delta);
                    node.fixedPoints = static_cast<uint8_t>(initialFixedPoints
                            + (omega0[c] == a) + (omega0[a] == b) + (omega0[b] == c));
                    // Accept the first successful prefix as soon as it is
                    // generated, before it can wait behind queued descendants.
                    const int kind = solution_kind(ctx, node.oddCycles, node.fixedPoints, node.depth);
                    if (kind != 0) {
                        if (atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                            *ctx.solutionDepth = node.depth;
                            *ctx.solutionKind = kind;
                            ctx.solutionMoves[0] = node.moves[0];
                        }
                        break;
                    }
                    // betaStart: first element of beta cycle after block transposition
                    // transposed_src_index(0, i, j, k) = j when i==0, else 0
                    node.betaStart = (moveFirst == 0)
                        ? static_cast<uint8_t>(beta0[moveSecond])
                        : static_cast<uint8_t>(beta0[0]);

                    queue.nodes[rootCount++] = node;
                }
            }

            queue.tail = (rootCount == localCapacity) ? 0 : rootCount;
            queue.size = rootCount;
            queues[queueIndex] = queue;
            if (rootCount > 0) {
                atomicAdd(outstandingWork, static_cast<unsigned long long>(rootCount));
            }
        }

        if (blockIdx.x == 0 && threadIdx.x == 0) {
            overflow->head = 0;
            overflow->tail = 0;
            overflow->size = 0;
            overflow->lock = 0;
            overflow->capacity = overflowCapacity;
            overflow->nodes = overflowNodes;
        }
    }

    // ─── Persistent search kernel ────────────────────────────────────────────────

    __global__ void __launch_bounds__(32)
    sorting_search_persistent_kernel(const DeviceSearchContext ctx) {
        const int warpId = static_cast<int>(blockIdx.x);
        const int lane = static_cast<int>(threadIdx.x);

        __shared__ uint8_t sh_parent_omega[MAX_N];
        __shared__ uint8_t sh_parent_beta[MAX_N];
        __shared__ uint8_t sh_tmp[MAX_N];
        __shared__ uint8_t sh_active_positions[MAX_N];
        __shared__ AlgebraicCycleMetadata<MAX_N> sh_cycle_metadata;
        __shared__ BacktrackingNode sh_children[WARP_SIZE];
        // The whole warp expands one parent. Keeping its variable-indexed
        // move history here avoids a separate local-memory array per lane.
        __shared__ BacktrackingNode node;
#if __CUDA_ARCH__ >= 700
        unsigned idleBackoff = 64;
#endif
        int stealOffset = 1;

        while (true) {
            if (warp_should_stop(ctx)) return;

            bool hasNode = false;

            if (lane == 0) {
                hasNode = owner_pop(ctx.queues[warpId], node);
                if (!hasNode) {
                    const int victimCount = ctx.queueCount - 1;
                    const int probeCount = min(victimCount, 8);
                    for (int probe = 0; probe < probeCount; ++probe) {
                        const int offset = 1 + ((stealOffset - 1 + probe) % victimCount);
                        const int victim = (warpId + offset) % ctx.queueCount;
                        if (stealer_pop(ctx.queues[victim], node)) { hasNode = true; break; }
                    }
                    if (victimCount > 0) {
                        stealOffset = 1 + ((stealOffset - 1 + probeCount) % victimCount);
                    }
                }
                if (!hasNode) {
                    hasNode = stealer_pop(*ctx.overflow, node);
                }
            }

            int foundNode = __shfl_sync(0xFFFFFFFF, hasNode ? 1 : 0, 0);
            if (!foundNode) {
                int noWork = 0;
                if (lane == 0) {
                    noWork = load_relaxed(ctx.outstandingWork) == 0 ? 1 : 0;
                }
                noWork = __shfl_sync(0xFFFFFFFF, noWork, 0);
                if (noWork != 0) return;
#if __CUDA_ARCH__ >= 700
                __nanosleep(idleBackoff);
                idleBackoff = min(idleBackoff * 2U, 1024U);
#endif
                continue;
            }
#if __CUDA_ARCH__ >= 700
            idleBackoff = 64;
#endif

            // Queue removal writes the shared parent from lane zero. Publish
            // those writes before any lane reads the state or move history.
            __syncwarp(0xFFFFFFFF);
            if (node.depth == 0 || node.depth > ctx.searchDepth || node.depth > MAX_DEPTH) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }

            const int parentOddCycles = node.oddCycles;

            // Neither accepting a prefix nor pruning a node needs its beta cycle.
            const int parentKind = solution_kind(ctx, parentOddCycles, node.fixedPoints, node.depth);
            if (parentKind != 0) {
                if (atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                    *ctx.solutionDepth = node.depth;
                    *ctx.solutionKind = parentKind;
                    for (int d = 0; d < node.depth; ++d) ctx.solutionMoves[d] = node.moves[d];
                }
                return;
            }

            if (node.depth >= ctx.searchDepth || !can_still_reach(ctx, parentOddCycles, node.depth)) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }

            int dominated = 0;
            if (lane == 0) dominated = memo_dominated(ctx, node);
            if (__shfl_sync(0xFFFFFFFF, dominated, 0)) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }

            // With no zero-move allowance left, every continuing move must
            // lower rank (n - oddCycles)/2 by one. An all-odd permutation then
            // stays all odd (Muehle--Nadeau, Corollary 3.3). At rank r > 0 its
            // contracted domain has at least 2r+1 symbols. If even the lowest
            // rank reachable before the exploration cap exceeds every rank
            // admitting a diameter closure, this entire subtree is unresolved.
            // The host derives the threshold from the actual diameter table.
            if (ctx.maxDepth - ctx.searchDepth > ctx.maxAllOddClosureRank
                    && node.depth + (ctx.n - parentOddCycles) / 2 == ctx.maxDepth) {
                int allOdd = 0;
                if (lane == 0) allOdd = !has_even_length_cycle(node.omega, ctx.n);
                if (__shfl_sync(0xFFFFFFFF, allOdd, 0)) {
                    if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                    continue;
                }
            }

            // Store parent omega in shared memory, reconstruct beta via rho invariant
            if (lane == 0) {
                for (int i = 0; i < ctx.n; ++i)
                    sh_parent_omega[i] = node.omega[i];

                // Reconstruct beta_cycle from rho_inv and omega:
                // beta_func_inv[x] = rho_inv[omega[x]], invert to get beta_func
                for (int x = 0; x < ctx.n; ++x) {
                    const uint8_t y = ctx.rhoInv[sh_parent_omega[x]];
                    sh_parent_beta[y] = static_cast<uint8_t>(x);  // beta_func[y] = x
                }
                // Traverse cycle from betaStart to build beta_cycle sequence
                uint8_t cur = node.betaStart;
                for (int pos = 0; pos < ctx.n; ++pos) {
                    sh_tmp[pos] = cur;
                    cur = sh_parent_beta[cur];
                }
                for (int i = 0; i < ctx.n; ++i)
                    sh_parent_beta[i] = sh_tmp[i];
                // Contract fixed symbols for support enumeration, retaining
                // original beta positions so every recorded move replays on
                // the full pair. Retained triples keep their original order.
                int active = 0;
                for (int pos = 0; pos < ctx.n; ++pos) {
                    const int symbol = sh_parent_beta[pos];
                    if (sh_parent_omega[symbol] != symbol)
                        sh_active_positions[active++] = static_cast<uint8_t>(pos);
                }
            }
            __syncwarp(0xFFFFFFFF);

            const int childDepth = node.depth + 1;
            // The consecutive-triple lemma lists every move gaining at least
            // two fixed points in O(N) supports. Seek a one-move exit before
            // generating and queueing general children. Fixed symbols are
            // virtually contracted: their deletion preserves support order.
            if (solution_kind(ctx, parentOddCycles + 2,
                              min(ctx.n, static_cast<int>(node.fixedPoints) + 3), childDepth) != 0) {
                if (lane < ctx.n) sh_tmp[sh_parent_beta[lane]] = static_cast<uint8_t>(lane);
                __syncwarp(0xFFFFFFFF);
                int fastKind = 0;
                short first = 0, second = 0, third = 0;
                if (lane < ctx.n) {
                    const int a = lane;
                    const int b = sh_parent_omega[a];
                    const int c = sh_parent_omega[b];
                    if (a != b && b != c && c != a) {
                        const int gain = 2 + (sh_parent_omega[c] == a);
                        const int pa = sh_tmp[a], pb = sh_tmp[b], pc = sh_tmp[c];
                        if ((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb)) {
                            fastKind = solution_kind(ctx, parentOddCycles + 2,
                                                     node.fixedPoints + gain, childDepth);
                            first = static_cast<short>(min(pa, min(pb, pc)));
                            third = static_cast<short>(max(pa, max(pb, pc)));
                            second = static_cast<short>(pa + pb + pc - first - third);
                        }
                    }
                }
                const unsigned fastBallot = __ballot_sync(0xFFFFFFFF, fastKind != 0);
                if (fastBallot != 0) {
                    const int solutionLane = __ffs(fastBallot) - 1;
                    if (lane == solutionLane && atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                        *ctx.solutionDepth = childDepth;
                        *ctx.solutionKind = fastKind;
                        for (int d = 0; d < node.depth; ++d) ctx.solutionMoves[d] = node.moves[d];
                        ctx.solutionMoves[node.depth] = pack_move(first, second, third);
                    }
                    return;
                }
            }
            // At the final depth a closure must leave at most two nonfixed
            // symbols. With one move remaining, the parent would already
            // close if it had at most three, so any new closure gains at least
            // two fixed points. Without closures, a move reaching identity
            // fixes its three symbols. Both are among the consecutive triples
            // just tested; no general child can be queued at this depth.
            if (childDepth == ctx.maxDepth) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }
            // At an exploration cap, no child can be expanded. The fast scan
            // above tested every support gaining at least two fixed symbols.
            // Skip general enumeration only when neither gain zero nor gain
            // one can close using the actual full-budget diameter table.
            // This also handles unknown diameters without assuming a bound.
            if (childDepth == ctx.searchDepth) {
                bool lowGainCanClose = false;
                for (int gain = 0; gain <= 1; ++gain) {
                    const int remaining = ctx.n - node.fixedPoints - gain;
                    if (remaining < 0) continue;
                    const int diameter = ctx.knownDiameters[remaining];
                    lowGainCanClose |= remaining == 0 || (ctx.allowDiameterClosure
                            && diameter != INT_MAX && diameter <= ctx.maxDepth - childDepth);
                }
                if (!lowGainCanClose) {
                    if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                    continue;
                }
            }
            // Only general support enumeration needs cycle metadata. Terminal
            // nodes and fast closures above avoid this serial cycle traversal.
            if (lane == 0) build_cycle_metadata(sh_parent_omega, ctx.n, sh_cycle_metadata);
            __syncwarp(0xFFFFFFFF);
            const int activeCount = ctx.n - node.fixedPoints;
            const int activeTriples = combinations_of_three(activeCount);
            const int activeOffset = triple_table_offset(activeCount);
            short undoFirst, previousSplit, undoThird;
            unpack_move(node.moves[node.depth - 1], undoFirst, previousSplit, undoThird);
            const int undoSecond = undoFirst + undoThird - previousSplit;
            for (int tBase = 0; tBase < activeTriples; tBase += WARP_SIZE) {
                if (warp_should_stop(ctx)) return;

                const int t = ctx.reverseTripleOrder
                    ? (activeTriples - 1 - tBase - lane)
                    : (tBase + lane);
                bool foundChild = false;
                bool wantsPush = false;
                short solutionFirst{}, solutionSecond{}, solutionThird{};
                int childOddCycles = 0;
                int childFixedPoints = 0;
                int childKind = 0;
                uint8_t childBetaStart = 0;
                uint8_t child_a = 0, child_b = 0, child_c = 0;
                short moveFirst = 0, moveSecond = 0, moveThird = 0;

                if (t >= 0 && t < activeTriples) {
                    moveFirst = sh_active_positions[ctx.tripleFirst[activeOffset + t]];
                    moveSecond = sh_active_positions[ctx.tripleSecond[activeOffset + t]];
                    moveThird = sh_active_positions[ctx.tripleThird[activeOffset + t]];
                    const uint8_t a = sh_parent_beta[moveFirst];
                    const uint8_t b = sh_parent_beta[moveSecond];
                    const uint8_t c = sh_parent_beta[moveThird];

                    // Reversing the preceding segment interchange returns to the
                    // identical pair with two fewer moves available. Removing
                    // that adjacent inverse pair preserves every possible closure.
                    const bool immediateUndo = moveFirst == undoFirst
                            && moveSecond == undoSecond && moveThird == undoThird;
                    if (!immediateUndo && !contains_fixed_symbol(sh_parent_omega, a, b, c)) {
                        const int delta = metadata_odd_cycle_delta(sh_cycle_metadata, a, b, c);

                        if (delta == 0 || delta == 2) {
                            childOddCycles = parentOddCycles + delta;

                            childFixedPoints = node.fixedPoints + (sh_parent_omega[c] == a)
                                    + (sh_parent_omega[a] == b) + (sh_parent_omega[b] == c);
                            childKind = solution_kind(ctx, childOddCycles, childFixedPoints, childDepth);
                            if (childKind != 0) {
                                foundChild = true;
                                solutionFirst = moveFirst;
                                solutionSecond = moveSecond;
                                solutionThird = moveThird;
                            } else if (childDepth < ctx.searchDepth && can_still_reach(ctx, childOddCycles, childDepth)) {
                                child_a = a;
                                child_b = b;
                                child_c = c;
                                // betaStart only changes when moveFirst == 0
                                childBetaStart = (moveFirst == 0)
                                    ? sh_parent_beta[moveSecond]
                                    : node.betaStart;
                                wantsPush = true;
                            }
                        }
                    }
                }

                const unsigned foundBallot = __ballot_sync(0xFFFFFFFF, foundChild ? 1 : 0);
                if (foundBallot != 0) {
                    const int solutionLane = __ffs(foundBallot) - 1;
                    if (lane == solutionLane && atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                        *ctx.solutionDepth = childDepth;
                        *ctx.solutionKind = childKind;
                        for (int d = 0; d < node.depth; ++d) ctx.solutionMoves[d] = node.moves[d];
                        ctx.solutionMoves[node.depth] = pack_move(
                                solutionFirst, solutionSecond, solutionThird);
                    }
                    return;
                }

                // Warp-cooperative push: stage children in shared memory, batch-push
                unsigned pushBallot = __ballot_sync(0xFFFFFFFF, wantsPush ? 1 : 0);
                const int pushCount = __popc(pushBallot);
                if (pushCount > 0) {
                    if (wantsPush) {
                        const unsigned mask_below = (1u << lane) - 1u;
                        const int mySlot = __popc(pushBallot & mask_below);
                        sh_children[mySlot].depth = static_cast<uint8_t>(childDepth);
                        sh_children[mySlot].oddCycles = static_cast<uint8_t>(childOddCycles);
                        sh_children[mySlot].fixedPoints = static_cast<uint8_t>(childFixedPoints);
                        for (int d = 0; d < node.depth; ++d)
                            sh_children[mySlot].moves[d] = node.moves[d];
                        sh_children[mySlot].moves[node.depth] = pack_move(moveFirst, moveSecond, moveThird);
                        for (int i = 0; i < ctx.n; ++i)
                            sh_children[mySlot].omega[i] = sh_parent_omega[i];
                        sh_children[mySlot].omega[child_a] = sh_parent_omega[child_c];
                        sh_children[mySlot].omega[child_b] = sh_parent_omega[child_a];
                        sh_children[mySlot].omega[child_c] = sh_parent_omega[child_b];
                        sh_children[mySlot].betaStart = childBetaStart;
                    }
                    __syncwarp(0xFFFFFFFF);

                    BacktrackingQueue &q = ctx.queues[warpId];
                    int pushed = 0;
                    int firstQueueIndex = 0;
                    if (lane == 0) {
                        // Reserve every child before publishing any queue entry.
                        // The active parent remains counted until the entire
                        // expansion finishes, so the count cannot transiently
                        // reach zero while work is in flight.
                        atomicAdd(ctx.outstandingWork,
                                  static_cast<unsigned long long>(pushCount));

                        // Retain the owner lock while all lanes copy its batch.
                        // No stealer can observe a partly initialized entry.
                        acquire_lock(&q.lock);
                        pushed = min(pushCount, q.capacity - q.size);
                        firstQueueIndex = q.tail;
                    }
                    pushed = __shfl_sync(0xFFFFFFFF, pushed, 0);
                    firstQueueIndex = __shfl_sync(0xFFFFFFFF, firstQueueIndex, 0);

                    static_assert(alignof(BacktrackingNode) >= alignof(uint32_t),
                                  "Cooperative queue copies require aligned nodes");
                    static_assert(sizeof(BacktrackingNode) % sizeof(uint32_t) == 0,
                                  "Cooperative queue copies require whole words");
                    constexpr int wordsPerNode = sizeof(BacktrackingNode) / sizeof(uint32_t);
                    for (int word = lane; word < pushed * wordsPerNode; word += WARP_SIZE) {
                        const int entry = word / wordsPerNode;
                        const int wordInNode = word - entry * wordsPerNode;
                        int queueIndex = firstQueueIndex + entry;
                        if (queueIndex >= q.capacity) queueIndex -= q.capacity;
                        // Consecutive destinations retain the original reverse
                        // child order. memcpy preserves object representations
                        // without aliasing a BacktrackingNode through uint32_t.
                        uint32_t value;
                        memcpy(&value,
                               reinterpret_cast<const char *>(&sh_children[pushCount - 1 - entry])
                                       + wordInNode * sizeof(uint32_t), sizeof(value));
                        memcpy(reinterpret_cast<char *>(&q.nodes[queueIndex])
                                       + wordInNode * sizeof(uint32_t), &value, sizeof(value));
                    }
                    // Each copying lane publishes its own global stores before
                    // lane zero updates queue metadata and releases the lock.
                    if (pushed > 0) __threadfence();
                    __syncwarp(0xFFFFFFFF);
                    if (lane == 0) {
                        int nextQueueIndex = firstQueueIndex + pushed;
                        if (nextQueueIndex >= q.capacity) nextQueueIndex -= q.capacity;
                        q.tail = nextQueueIndex;
                        q.size += pushed;
                        if (pushed > 0) __threadfence();
                        release_lock(&q.lock);

                        // Handle overflow for remaining children
                        for (int c = pushCount - 1 - pushed; c >= 0; --c) {
                            bool donated = false;
                            for (int i = 1; i < ctx.queueCount; ++i) {
                                int receiver = (warpId + i) % ctx.queueCount;
                                if (donate_push(ctx.queues[receiver], sh_children[c])) {
                                    donated = true;
                                    break;
                                }
                            }
                            if (!donated) {
                                if (!donate_push(*ctx.overflow, sh_children[c])) {
                                    atomicExch(ctx.overflowFlag, 1);
                                }
                            }
                        }
                    }
                    __syncwarp(0xFFFFFFFF);
                }
            }
            if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
        }
    }

    int getQueueCount(const int device) {
        static std::mutex queueCountMutex;
        static std::vector<int> queueCounts;

        std::lock_guard<std::mutex> lock(queueCountMutex);
        if (static_cast<size_t>(device) >= queueCounts.size()) {
            queueCounts.resize(static_cast<size_t>(device) + 1, 0);
        }

        int &cached = queueCounts[static_cast<size_t>(device)];
        if (cached > 0) return cached;

        int multiprocessors = 0;
        CHECK_CUDA(cudaDeviceGetAttribute(&multiprocessors, cudaDevAttrMultiProcessorCount, device));
        int activeBlocksPerSM = 1;
        CHECK_CUDA(cudaOccupancyMaxActiveBlocksPerMultiprocessor(
                &activeBlocksPerSM, sorting_search_persistent_kernel, WARP_SIZE, 0));
        cudaDeviceProp props{};
        CHECK_CUDA(cudaGetDeviceProperties(&props, device));

        const int occupancyTarget = std::max(1, multiprocessors * std::max(1, activeBlocksPerSM));
        const int gridXLimit = std::max(1, props.maxGridSize[0]);
        cached = std::min(occupancyTarget, gridXLimit);
        return cached;
    }

    int getQueueCountForSlot(const int device,
                             const int slotsOnDevice,
                             const int slotOrdinalOnDevice) {
        const int fullQueueCount = getQueueCount(device);
        const int divisor = std::max(1, slotsOnDevice);
        if (divisor > fullQueueCount) {
            throw std::invalid_argument(
                    "slots per device exceeds the persistent kernel's resident-block capacity");
        }
        const int base = std::max(1, fullQueueCount / divisor);
        const int remainder = fullQueueCount % divisor;
        return base + (slotOrdinalOnDevice >= 0 && slotOrdinalOnDevice < remainder ? 1 : 0);
    }

    struct SlotAllocation {
        int queueCount;
        int localCapacity;
        int overflowCapacity;
    };

    // Keep preflight and allocation on the same capacity calculation, including
    // the minimum queues used when the configured budget is very small.
    SlotAllocation slotAllocation(const int device, const size_t queueBytesBudget,
                                  const int slotsOnDevice, const int slotOrdinalOnDevice) {
        const int queueCount = getQueueCountForSlot(device, slotsOnDevice, slotOrdinalOnDevice);
        const size_t overflowBudget = queueBytesBudget / 4;
        const size_t localBudget = queueBytesBudget - overflowBudget;
        const size_t localCapacity = std::max(size_t{2}, localBudget / (queueCount * sizeof(BacktrackingNode)));
        const size_t overflowCapacity = std::max(size_t{1024}, overflowBudget / sizeof(BacktrackingNode));
        if (localCapacity > static_cast<size_t>(std::numeric_limits<int>::max())
            || overflowCapacity > static_cast<size_t>(std::numeric_limits<int>::max())) {
            throw std::invalid_argument("GPU queue capacity exceeds the supported integer range");
        }
        return {queueCount, static_cast<int>(localCapacity), static_cast<int>(overflowCapacity)};
    }
}

size_t gpuSortingSearchSlotBytes(const int device, const size_t queueBytesBudget,
                                const int slotsOnDevice, const int slotOrdinalOnDevice) {
    const auto plan = slotAllocation(device, queueBytesBudget, slotsOnDevice, slotOrdinalOnDevice);
    const size_t nodeCount = static_cast<size_t>(plan.queueCount) * plan.localCapacity + plan.overflowCapacity;
    const size_t maxTriples = static_cast<size_t>(triple_table_offset(MAX_N + 1));
    return nodeCount * sizeof(BacktrackingNode)
           + (static_cast<size_t>(plan.queueCount) + 1) * sizeof(BacktrackingQueue)
           + 3 * maxTriples * sizeof(short)
           + 2 * MAX_N * sizeof(short)
           + sizeof(BacktrackingResult)
           + MEMO_CAPACITY * sizeof(MemoEntry);
}

void allocateGpuSortingSearchSlot(const int slot, const int device,
                                   const size_t queueBytesBudget,
                                   const int slotsOnDevice,
                                   const int slotOrdinalOnDevice) {
    std::lock_guard<std::mutex> lock(gSlotsMutex);
    if (static_cast<size_t>(slot) >= gSlots.size()) {
        gSlots.resize(static_cast<size_t>(slot) + 1);
    }

    auto &buffers = gSlots[slot];
    buffers.device = device;

    const auto plan = slotAllocation(device, queueBytesBudget, slotsOnDevice, slotOrdinalOnDevice);
    const int queueCount = plan.queueCount;
    const int localCapacity = plan.localCapacity;
    const int overflowCapacity = plan.overflowCapacity;

    buffers.queues = allocateDeviceBuffer<BacktrackingQueue>(
            static_cast<size_t>(queueCount), "sort.queues");
    buffers.overflow = allocateDeviceBuffer<BacktrackingQueue>(1, "sort.overflowQueue");
    buffers.nodes = allocateDeviceBuffer<BacktrackingNode>(
            static_cast<size_t>(queueCount) * static_cast<size_t>(localCapacity), "sort.nodes");
    buffers.overflowNodes = allocateDeviceBuffer<BacktrackingNode>(
            static_cast<size_t>(overflowCapacity), "sort.overflowNodes");
    const size_t maxTriples = static_cast<size_t>(triple_table_offset(MAX_N + 1));
    buffers.tripleFirst = allocateDeviceBuffer<short>(maxTriples, "sort.tripleFirst");
    buffers.tripleSecond = allocateDeviceBuffer<short>(maxTriples, "sort.tripleSecond");
    buffers.tripleThird = allocateDeviceBuffer<short>(maxTriples, "sort.tripleThird");
    buffers.inputVectors = allocateDeviceBuffer<short>(
            static_cast<size_t>(MAX_N) * 2, "sort.inputVectors");
    buffers.result = allocateDeviceBuffer<BacktrackingResult>(1, "sort.result");
    buffers.hostResult = makePinnedHostBuffer<BacktrackingResult>(1);
    buffers.memo = allocateDeviceBuffer<MemoEntry>(MEMO_CAPACITY, "sort.memo");

    buffers.queueCount = queueCount;
    buffers.localCapacity = localCapacity;
    buffers.overflowCapacity = overflowCapacity;
    buffers.cachedTriplesN = -1;
}

void destroyGpuSortingSearchSlots() {
    std::lock_guard<std::mutex> lock(gSlotsMutex);
    int originalDevice = -1;
    CHECK_CUDA(cudaGetDevice(&originalDevice));

    for (auto &buffers : gSlots) {
        if (buffers.device < 0) continue;
        if (originalDevice != buffers.device) CHECK_CUDA(cudaSetDevice(buffers.device));
        releaseSlotBuffers(buffers);
        buffers.device = -1;
    }

    gSlots.clear();
    if (originalDevice >= 0) CHECK_CUDA(cudaSetDevice(originalDevice));
}

GpuSortingResult performGpuSortingSearch(std::vector<short> beta,
                                          std::vector<short> omega,
                                          const int maximumDepth,
                                          const bool allowDiameterClosure,
                                          const int maximumSearchDepth,
                                          const unsigned long long maximumNanos,
                                          const std::vector<int> &knownDiameters,
                                          const cudaStream_t stream,
                                          const int slot,
                                          const int maxWarps) {
    if (omega.size() != beta.size()) {
        throw std::invalid_argument("performGpuSortingSearch: inconsistent input sizes");
    }
    const int n = static_cast<int>(omega.size());
    if (n > MAX_N) throw std::invalid_argument("performGpuSortingSearch: n exceeds MAX_N");
    if (maximumDepth <= 0 || maximumDepth > MAX_DEPTH) throw std::invalid_argument("performGpuSortingSearch: invalid maximumDepth");
    if (maximumSearchDepth < 1 || maximumSearchDepth > maximumDepth)
        throw std::invalid_argument("Invalid exploration depth");
    if (knownDiameters.size() != MAX_N + 1) throw std::invalid_argument("Invalid diameter table size");
    uint8_t initialOmega[MAX_N];
    for (int i = 0; i < n; ++i) initialOmega[i] = static_cast<uint8_t>(omega[i]);
    const int initialFixedPoints = count_fixed_points(initialOmega, n);
    if (initialFixedPoints == n) return GpuSortingResult{1, {}};
    const int initialDiameter = knownDiameters[n - initialFixedPoints];
    if (allowDiameterClosure && initialDiameter != INT_MAX && initialDiameter <= maximumDepth)
        return GpuSortingResult{2, {}};
    const int initialOddCycles = count_odd_cycles(initialOmega, n);
    if ((n - initialOddCycles + 1) / 2 > maximumDepth) return {};
    const int tripleCount = combinations_of_three(n);
    if (tripleCount == 0) return {};

    int currentDevice = 0;
    CHECK_CUDA(cudaGetDevice(&currentDevice));

    int queueCount;
    int localCapacity;
    int overflowCapacity;
    if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) {
        throw std::invalid_argument("invalid slot");
    }
    const auto &buffers = gSlots[slot];
    queueCount = buffers.queueCount;
    localCapacity = buffers.localCapacity;
    overflowCapacity = buffers.overflowCapacity;
    if (maxWarps > 0 && maxWarps < queueCount) queueCount = maxWarps;
    queueCount = std::min(queueCount, tripleCount);

    const auto slotView = getSlotBufferView(slot, n);

    if (slotView.triplesNeedUpload) {
        std::vector<short> firsts, seconds, thirds;
        const int tableCount = triple_table_offset(n + 1);
        firsts.reserve(static_cast<size_t>(tableCount));
        seconds.reserve(static_cast<size_t>(tableCount));
        thirds.reserve(static_cast<size_t>(tableCount));
        for (short domain = 3; domain <= n; ++domain) {
            for (short i = 0; i < domain - 2; ++i) {
                for (short j = i + 1; j < domain - 1; ++j) {
                    for (short k = j + 1; k < domain; ++k) {
                        firsts.push_back(i);
                        seconds.push_back(j);
                        thirds.push_back(k);
                    }
                }
            }
        }
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleFirst, firsts.data(),
                                   sizeof(short) * tableCount, cudaMemcpyHostToDevice, stream));
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleSecond, seconds.data(),
                                   sizeof(short) * tableCount, cudaMemcpyHostToDevice, stream));
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleThird, thirds.data(),
                                   sizeof(short) * tableCount, cudaMemcpyHostToDevice, stream));
    }

    auto inputVectors = std::vector<short>(static_cast<size_t>(n) * 2);
    std::copy(beta.begin(), beta.end(), inputVectors.begin());
    std::copy(omega.begin(), omega.end(), inputVectors.begin() + n);
    CHECK_CUDA(cudaMemcpyAsync(slotView.inputVectors, inputVectors.data(),
                               sizeof(short) * inputVectors.size(), cudaMemcpyHostToDevice, stream));

    BacktrackingResult initialResult{};
    initialResult.solutionDepth = -1;
    CHECK_CUDA(cudaMemcpyAsync(slotView.result, &initialResult, sizeof(BacktrackingResult),
                               cudaMemcpyHostToDevice, stream));

    const short *dBeta0 = slotView.inputVectors;
    const short *dOmega0 = slotView.inputVectors + n;
    auto *resultBytes = reinterpret_cast<char *>(slotView.result);
    auto *dFound = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, found));
    auto *dSolutionDepth = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, solutionDepth));
    auto *dSolutionKind = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, solutionKind));
    auto *dOverflow = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, overflowed));
    auto *dTimedOut = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, timedOut));
    auto *dSolutionMoves = reinterpret_cast<PackedMove *>(resultBytes + offsetof(BacktrackingResult, solutionMoves));
    auto *dOutstandingWork = reinterpret_cast<unsigned long long *>(
            resultBytes + offsetof(BacktrackingResult, outstandingWork));
    auto *dStartedAtNanos = reinterpret_cast<unsigned long long *>(
            resultBytes + offsetof(BacktrackingResult, startedAtNanos));

    DeviceSearchContext host{};
    host.tripleCount = tripleCount;
    host.n = n;
    host.maxDepth = maximumDepth;
    host.searchDepth = maximumSearchDepth;
    host.queueCount = queueCount;
    host.allowDiameterClosure = allowDiameterClosure ? 1 : 0;
    std::copy(knownDiameters.begin(), knownDiameters.end(), host.knownDiameters);
    // Identity has rank zero. Any nonidentity all-odd state of rank r has
    // at least 2r+1 nonfixed symbols. Relax all other partition constraints,
    // so this maximum remains safe for every supplied diameter table.
    host.maxAllOddClosureRank = 0;
    if (allowDiameterClosure) {
        for (int rank = 1; rank <= MAX_DEPTH; ++rank) {
            for (int domain = 2 * rank + 1; domain <= n; ++domain) {
                const int diameter = knownDiameters[domain];
                if (diameter != INT_MAX && diameter <= rank) {
                    host.maxAllOddClosureRank = rank;
                    break;
                }
            }
        }
    }

    // Compute rho_inv for beta reconstruction via the invariant:
    // rho = omega0 ∘ beta_func, where beta_func is the function form of the cycle beta0.
    // beta_func(beta0[i]) = beta0[(i+1) % n], so rho(x) = omega0(beta_func(x)).
    {
        uint8_t betaFunc[MAX_N];
        for (int i = 0; i < n; ++i)
            betaFunc[static_cast<uint8_t>(beta[i])] = static_cast<uint8_t>(beta[(i + 1) % n]);

        uint8_t rho[MAX_N];
        for (int i = 0; i < n; ++i)
            rho[i] = static_cast<uint8_t>(omega[betaFunc[i]]);

        for (int i = 0; i < n; ++i)
            host.rhoInv[rho[i]] = static_cast<uint8_t>(i);
    }

    host.tripleFirst = slotView.tripleFirst;
    host.tripleSecond = slotView.tripleSecond;
    host.tripleThird = slotView.tripleThird;
    host.omega0 = dOmega0;
    host.beta0 = dBeta0;
    host.queues = slotView.queues;
    host.overflow = slotView.overflow;
    host.foundFlag = dFound;
    host.solutionDepth = dSolutionDepth;
    host.solutionKind = dSolutionKind;
    host.solutionMoves = dSolutionMoves;
    host.overflowFlag = dOverflow;
    host.timeoutFlag = dTimedOut;
    host.outstandingWork = dOutstandingWork;
    host.startedAtNanos = dStartedAtNanos;
    host.maximumNanos = maximumNanos;
    host.memo = buffers.memo.get();
    CHECK_CUDA(cudaMemsetAsync(host.memo, 0, MEMO_CAPACITY * sizeof(MemoEntry), stream));
    host.reverseTripleOrder = (queueCount == 1) ? 1 : 0;

    constexpr int INIT_THREADS = 128;
    const int initBlocks = (queueCount + INIT_THREADS - 1) / INIT_THREADS;
    initialize_queues_kernel<<<initBlocks, INIT_THREADS, 0, stream>>>(
            host,
            slotView.tripleFirst + triple_table_offset(n),
            slotView.tripleSecond + triple_table_offset(n),
            slotView.tripleThird + triple_table_offset(n),
            tripleCount,
            slotView.queues, queueCount,
            slotView.nodes, localCapacity,
            slotView.overflow, slotView.overflowNodes, overflowCapacity,
            dOverflow, dOutstandingWork, dOmega0, dBeta0, n, initialOddCycles, initialFixedPoints,
            (queueCount == 1) ? 1 : 0);
    CHECK_CUDA(cudaGetLastError());

    sorting_search_persistent_kernel<<<queueCount, WARP_SIZE, 0, stream>>>(host);
    CHECK_CUDA(cudaGetLastError());

    CHECK_CUDA(cudaMemcpyAsync(slotView.hostResult, slotView.result,
                               sizeof(BacktrackingResult), cudaMemcpyDeviceToHost, stream));
    const cudaEvent_t completionEvent = getGpuSortSlotCompletionEvent(slot);
    if (completionEvent == nullptr) {
        throw std::runtime_error("performGpuSortingSearch: missing slot completion event");
    }
    CHECK_CUDA(cudaEventRecord(completionEvent, stream));
    CHECK_CUDA(cudaEventSynchronize(completionEvent));
    if (slotView.triplesNeedUpload) {
        markSlotTriplesUploaded(slot, currentDevice, n);
    }

    const BacktrackingResult resultSnapshot = *slotView.hostResult;
    const int found = resultSnapshot.found;
    const int solutionDepth = resultSnapshot.solutionDepth;
    const int overflowed = resultSnapshot.overflowed;
    if (!found && overflowed != 0) {
        throw GpuMemoryExhaustedException("GPU sorting search queue overflow");
    }
    // A concurrent valid witness wins over cancellation. A timeout carries no
    // witness and never claims that the permitted search tree was exhausted.
    if (!found && resultSnapshot.timedOut != 0) return GpuSortingResult{3, {}};
    if (!found) return GpuSortingResult{maximumSearchDepth < maximumDepth ? 4 : 0, {}};
    if (solutionDepth <= 0) throw std::runtime_error("Invalid solution depth");

    std::vector<Move> result;
    for (int d = 0; d < solutionDepth; ++d) {
        short i, j, k;
        unpack_move(resultSnapshot.solutionMoves[d], i, j, k);
        result.push_back(Move{i, j, k});
    }
    return GpuSortingResult{resultSnapshot.solutionKind, std::move(result)};
}
