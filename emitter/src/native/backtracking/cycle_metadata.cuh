#pragma once

#include <climits>
#include <cstdint>

#if defined(__CUDACC__)
#define TD20_CYCLE_INLINE __host__ __device__ __forceinline__
#else
#define TD20_CYCLE_INLINE inline
#endif

// Metadata belongs to one validated algebraic permutation. Cycle identifiers
// are the first symbol visited in each cycle; ranks increase along omega.
template<int MaximumSymbols>
struct AlgebraicCycleMetadata {
    uint8_t cycle[MaximumSymbols];
    uint8_t rank[MaximumSymbols];
    uint8_t length[MaximumSymbols]; // indexed by cycle identifier
};

template<int MaximumSymbols>
TD20_CYCLE_INLINE void build_cycle_metadata(
        const uint8_t *omega, const int n, AlgebraicCycleMetadata<MaximumSymbols> &metadata) {
    uint64_t visited = 0;
    for (int first = 0; first < n; ++first) {
        if (visited & (1ULL << first)) continue;
        int current = first, length = 0;
        do {
            visited |= 1ULL << current;
            metadata.cycle[current] = static_cast<uint8_t>(first);
            metadata.rank[current] = static_cast<uint8_t>(length++);
            current = omega[current];
        } while (current != first);
        metadata.length[first] = static_cast<uint8_t>(length);
    }
}

template<int MaximumSymbols>
TD20_CYCLE_INLINE int cycle_forward_distance(
        const AlgebraicCycleMetadata<MaximumSymbols> &metadata, const uint8_t a, const uint8_t b) {
    const int difference = static_cast<int>(metadata.rank[b]) - metadata.rank[a];
    return difference < 0 ? difference + metadata.length[metadata.cycle[a]] : difference;
}

template<int MaximumSymbols>
TD20_CYCLE_INLINE int two_cycle_odd_delta(
        const AlgebraicCycleMetadata<MaximumSymbols> &metadata,
        const uint8_t a, const uint8_t b, const uint8_t c) {
    // a,b share a cycle. The move keeps the a-to-b segment and joins its
    // complementary segment to c's cycle.
    const int firstLength = metadata.length[metadata.cycle[a]];
    const int secondLength = metadata.length[metadata.cycle[c]];
    const int distance = cycle_forward_distance(metadata, a, b);
    return (distance & 1) + ((firstLength - distance + secondLength) & 1)
            - (firstLength & 1) - (secondLength & 1);
}

template<int MaximumSymbols>
TD20_CYCLE_INLINE int metadata_odd_cycle_delta(
        const AlgebraicCycleMetadata<MaximumSymbols> &metadata,
        const uint8_t a, const uint8_t b, const uint8_t c) {
    const int cycleA = metadata.cycle[a];
    const int cycleB = metadata.cycle[b];
    const int cycleC = metadata.cycle[c];
    if (cycleA == cycleB && cycleB == cycleC) {
        const int ab = cycle_forward_distance(metadata, a, b);
        const int ac = cycle_forward_distance(metadata, a, c);
        if (ab > ac) return 0; // reverse cyclic order leaves one cycle
        const int length = metadata.length[cycleA];
        return (ab & 1) + ((ac - ab) & 1) + ((length - ac) & 1) - (length & 1);
    }
    if (cycleA == cycleB) return two_cycle_odd_delta(metadata, a, b, c);
    if (cycleB == cycleC) return two_cycle_odd_delta(metadata, b, c, a);
    if (cycleC == cycleA) return two_cycle_odd_delta(metadata, c, a, b);
    // Preserve the search's explicit rejection of three-cycle supports,
    // including those whose actual odd-length-cycle delta would be zero.
    return INT_MIN;
}

#undef TD20_CYCLE_INLINE
