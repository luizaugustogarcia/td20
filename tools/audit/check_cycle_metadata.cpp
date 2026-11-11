// CPU validation of the exact host/device helper used by CUDA descendants.
// Compile: c++ -O3 -std=c++17 tools/audit/check_cycle_metadata.cpp -o /tmp/td20-cycle-metadata
#include "../../emitter/src/native/backtracking/cycle_metadata.cuh"

#include <algorithm>
#include <array>
#include <chrono>
#include <iostream>
#include <numeric>
#include <random>
#include <stdexcept>

namespace {
    constexpr int MaximumSymbols = 21;
    using Permutation = std::array<uint8_t, MaximumSymbols>;

    int odd_cycles(const Permutation &omega, const int n) {
        std::array<bool, MaximumSymbols> seen{};
        int odd = 0;
        for (int first = 0; first < n; ++first) {
            if (seen[first]) continue;
            int current = first, length = 0;
            do {
                seen[current] = true;
                ++length;
                current = omega[current];
            } while (current != first);
            odd += length & 1;
        }
        return odd;
    }

    // Independent cycle membership obtained directly by following omega.
    bool same_cycle(const Permutation &omega, const int a, const int b) {
        int current = a;
        do {
            if (current == b) return true;
            current = omega[current];
        } while (current != a);
        return false;
    }

    unsigned long long check(const Permutation &omega, const int n) {
        AlgebraicCycleMetadata<MaximumSymbols> metadata;
        build_cycle_metadata(omega.data(), n, metadata);
        const int before = odd_cycles(omega, n);
        unsigned long long checked = 0;
        // Both orientations of every distinct support occur here, regardless
        // of any current-cycle order or fixed-point search restriction.
        for (int a = 0; a < n; ++a) for (int b = 0; b < n; ++b) for (int c = 0; c < n; ++c) {
            if (a == b || b == c || a == c) continue;
            auto child = omega;
            child[a] = omega[c]; child[b] = omega[a]; child[c] = omega[b];
            const int actual = odd_cycles(child, n) - before;
            const bool threeDifferent = !same_cycle(omega, a, b)
                    && !same_cycle(omega, b, c) && !same_cycle(omega, c, a);
            const int expected = threeDifferent ? INT_MIN : actual;
            const int obtained = metadata_odd_cycle_delta(metadata, a, b, c);
            if (obtained != expected) {
                std::cerr << "Cycle delta mismatch: n=" << n << " support=(" << a << ',' << b << ',' << c
                          << ") expected=" << expected << " actual=" << obtained << " omega=";
                for (int x = 0; x < n; ++x) std::cerr << static_cast<int>(omega[x]) << ',';
                std::cerr << '\n';
                throw std::runtime_error("Cycle metadata validation failed");
            }
            ++checked;
        }
        return checked;
    }
}

int main() {
    try {
        const auto started = std::chrono::steady_clock::now();
        unsigned long long permutations = 0, supports = 0;
        for (int n = 1; n <= 8; ++n) {
            Permutation omega{};
            std::iota(omega.begin(), omega.begin() + n, 0);
            do {
                supports += check(omega, n);
                ++permutations;
            } while (std::next_permutation(omega.begin(), omega.begin() + n));
        }
        // Reproducible larger domains include every support and both cyclic
        // orientations of each sampled permutation, through the GPU limit.
        std::mt19937 random(0x18C1C1E);
        unsigned long long sampledPermutations = 0, sampledSupports = 0;
        for (int n = 9; n <= MaximumSymbols; ++n) {
            Permutation omega{};
            std::iota(omega.begin(), omega.begin() + n, 0);
            sampledSupports += check(omega, n); // identity, including fixed points
            ++sampledPermutations;
            for (int sample = 0; sample < 128; ++sample) {
                std::shuffle(omega.begin(), omega.begin() + n, random);
                sampledSupports += check(omega, n);
                ++sampledPermutations;
            }
        }
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        std::cout << "cycle-metadata-v1: exhaustiveDomains=1-8 permutations=" << permutations
                  << " orderedSupports=" << supports << " sampledDomains=9-21 sampledPermutations=" << sampledPermutations
                  << " sampledOrderedSupports=" << sampledSupports << " seconds=" << seconds << '\n';
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
