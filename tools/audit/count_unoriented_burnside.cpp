// Exact native Burnside terms for totally-unoriented TD(20) roots.
//
// A totally-unoriented algebraic cycle is forced by its support: if the
// support is read in the current-cycle order, omega follows that order
// backwards.  The identity term can therefore be counted by selecting
// independent supports and rejecting a forced proper target cycle.  The
// nonidentity terms are generated independently from the centralizer and
// alternating-path descriptions used in the Java Burnside checker.
//
// Build with -O3 and -std=c++20.  run_unoriented_burnside.py documents and
// orchestrates the complete all-core calculation.
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr int MAX_N = 21;
using Perm = std::array<int, MAX_N>;

int gcd(int a, int b) {
    while (b != 0) { const int r = a % b; a = b; b = r; }
    return a;
}

std::vector<int> parse_partition(const std::string& text) {
    std::vector<int> result;
    std::size_t begin = 0;
    for (;;) {
        const auto end = text.find(',', begin);
        result.push_back(std::stoi(text.substr(begin, end - begin)));
        if (end == std::string::npos) break;
        begin = end + 1;
    }
    return result;
}

std::string json_partition(const std::vector<int>& partition) {
    std::string result = "[";
    for (std::size_t i = 0; i < partition.size(); ++i) {
        if (i != 0) result += ',';
        result += std::to_string(partition[i]);
    }
    return result + ']';
}

bool valid_root_partition(const std::vector<int>& partition, int n) {
    if (partition.empty() || std::accumulate(partition.begin(), partition.end(), 0) != n) return false;
    int even = 0;
    for (const int length : partition) {
        if (length < 2 || length > n) return false;
        even += (length & 1) == 0;
    }
    return (even & 1) == 0;
}

// Returns an ascending cycle-type key, or zero for a fixed point or an
// oriented cycle.  Five bits per part are sufficient through N=21.
std::uint64_t totally_unoriented_key(const Perm& omega, int n) {
    unsigned unseen = (1u << n) - 1u;
    std::array<int, MAX_N> lengths{};
    int cycles = 0;
    while (unseen != 0) {
        const int first = std::countr_zero(unseen);
        int current = first, length = 0, increases = 0;
        do {
            unseen ^= 1u << current;
            const int next = omega[current];
            increases += next > current;
            current = next;
            ++length;
        } while (current != first);
        if (length == 1 || (length >= 3 && increases != 1)) return 0;
        lengths[cycles++] = length;
    }
    std::sort(lengths.begin(), lengths.begin() + cycles);
    std::uint64_t key = 0;
    for (int i = 0; i < cycles; ++i) key = (key << 5) | static_cast<unsigned>(lengths[i]);
    return key;
}

std::uint64_t partition_key(std::vector<int> partition) {
    std::sort(partition.begin(), partition.end());
    std::uint64_t key = 0;
    for (const int length : partition) key = (key << 5) | static_cast<unsigned>(length);
    return key;
}

class IdentityCounter {
    int n_;
    std::vector<int> parts_;
    std::array<std::vector<unsigned>, MAX_N + 1> supports_;
    Perm omega_{};
    std::array<int, MAX_N> parent_{};
    std::array<int, MAX_N> size_{};
    std::array<int, MAX_N> child_trail_{};
    int edges_ = 0;
    int shard_index_;
    int shard_count_;
    int first_multiplicity_ = 1;

    int root(int vertex) const {
        while (parent_[vertex] != vertex) vertex = parent_[vertex];
        return vertex;
    }

    bool add_cycle(unsigned mask) {
        int previous = 31 - std::countl_zero(mask);
        for (unsigned bits = mask; bits != 0; bits &= bits - 1) {
            const int vertex = std::countr_zero(bits);
            const int source = vertex == 0 ? n_ - 1 : vertex - 1;
            int first = root(source), second = root(previous);
            if (first == second && edges_ + 1 < n_) return false;
            int child = -1;
            if (first != second) {
                if (size_[first] < size_[second]) std::swap(first, second);
                child = second; parent_[child] = first; size_[first] += size_[child];
            }
            child_trail_[edges_++] = child;
            omega_[vertex] = previous;
            previous = vertex;
        }
        return true;
    }

    void rollback(int before) {
        while (edges_ > before) {
            const int child = child_trail_[--edges_];
            if (child >= 0) { size_[parent_[child]] -= size_[child]; parent_[child] = child; }
        }
    }

    std::uint64_t accept_support(std::size_t index, unsigned available,
                                 unsigned previous_mask, unsigned mask) {
        const int length = parts_[index];
        // The first support is distinguished while counting.  When the
        // largest part is repeated, the other largest supports are ordered
        // only among themselves; division by the multiplicity in the runner
        // forgets the distinguished choice.
        if (index != 0 && length == parts_[index - 1]
                && !(index == 1 && first_multiplicity_ > 1)
                && mask <= previous_mask) return 0;
        const int before = edges_;
        const std::uint64_t result = add_cycle(mask)
                ? recurse(index + 1, available ^ mask, mask) : 0;
        rollback(before);
        return result;
    }

    std::uint64_t choose_support(std::size_t index, unsigned available,
                                 unsigned previous_mask, unsigned candidates,
                                 int needed, unsigned mask) {
        if (needed == 0)
            return accept_support(index, available, previous_mask, mask);
        if (std::popcount(candidates) < needed) return 0;
        const unsigned full = (1u << n_) - 1u;
        std::uint64_t total = 0;
        unsigned choices = candidates;
        while (choices != 0) {
            const unsigned bit = choices & (0u - choices);
            choices ^= bit;
            if (std::popcount(choices) < needed - 1) break;
            const unsigned left = ((bit << 1) & full) | (bit >> (n_ - 1));
            const unsigned right = (bit >> 1) | ((bit & 1u) << (n_ - 1));
            const unsigned neighbours = left | right;
            if ((mask & neighbours) == 0)
                total += choose_support(index, available, previous_mask,
                                        choices & ~neighbours, needed - 1,
                                        mask | bit);
        }
        return total;
    }

    std::uint64_t recurse(std::size_t index, unsigned available, unsigned previous_mask) {
        if (index == parts_.size()) return edges_ == n_ ? 1 : 0;
        const int length = parts_[index];
        if (index + 1 == parts_.size()) {
            // The last support is the whole remaining mask.  Scanning every
            // independent support to rediscover this single mask dominates
            // small-part partitions such as (3,3,3,3,3,3,3).
            if (std::popcount(available) != length) return 0;
            const unsigned full = (1u << n_) - 1u;
            const unsigned rotate_left = ((available << 1) & full)
                                       | (available >> (n_ - 1));
            if ((available & rotate_left) != 0
                    || (index != 0 && length == parts_[index - 1]
                        && available <= previous_mask)) return 0;
            const int before = edges_;
            const bool accepted = add_cycle(available) && edges_ == n_;
            rollback(before);
            return accepted ? 1 : 0;
        }
        // Enumerating only independent subsets of the remaining positions is
        // much cheaper than rescanning the domain-wide support table at every
        // deep node (for example, 15 pairs versus all 189 pairs near a leaf).
        return choose_support(index, available, previous_mask, available,
                              length, 0);
    }

public:
    IdentityCounter(std::vector<int> partition, int shard_index, int shard_count)
        : n_(std::accumulate(partition.begin(), partition.end(), 0)),
          parts_(std::move(partition)), shard_index_(shard_index), shard_count_(shard_count) {
        if (n_ < 3 || n_ > MAX_N || !valid_root_partition(parts_, n_))
            throw std::invalid_argument("Invalid fixed-point-free even partition");
        if (shard_count_ < 1 || shard_index_ < 0 || shard_index_ >= shard_count_)
            throw std::invalid_argument("Invalid shard");
        std::sort(parts_.rbegin(), parts_.rend());
        first_multiplicity_ = static_cast<int>(std::count(
                parts_.begin(), parts_.end(), parts_.front()));
        for (int i = 0; i < n_; ++i) { parent_[i] = i; size_[i] = 1; }
        if (parts_.front() > n_ / 2) return;
        const unsigned full = (1u << n_) - 1u;
        for (unsigned mask = 1; mask <= full; ++mask) {
            // No support may contain neighbours in the current cycle.
            const unsigned rotate_left = ((mask << 1) & full) | (mask >> (n_ - 1));
            if ((mask & rotate_left) == 0) supports_[std::popcount(mask)].push_back(mask);
        }
    }

    std::uint64_t run() {
        if (parts_.front() > n_ / 2) return 0;
        const unsigned full = (1u << n_) - 1u;
        std::uint64_t total = 0;
        int task = 0;
        for (const unsigned mask : supports_[parts_.front()]) {
            unsigned least = mask;
            unsigned rotated = mask;
            int orbit_size = 0;
            do {
                ++orbit_size;
                rotated = ((rotated << 1) & full) | (rotated >> (n_ - 1));
                least = std::min(least, rotated);
            } while (rotated != mask);
            if (mask != least) continue;
            if (task++ % shard_count_ != shard_index_) continue;
            const int before = edges_;
            if (add_cycle(mask)) total += static_cast<std::uint64_t>(orbit_size)
                    * recurse(1, full ^ mask, mask);
            rollback(before);
        }
        // The sum over every shard is divided by first_multiplicity_.  A
        // single shard need not be divisible: sharding distributes rotation
        // orbits of distinguished supports, while only their union contains
        // exactly one contribution per possible distinguished largest cycle.
        return total;
    }

    int count_divisor() const { return first_multiplicity_; }

};

// Small allocation-free table for symmetry-fixed all-unoriented types.  A
// symmetry traversal is shared by every requested partition; repeating the
// N=21 reflection traversal 83 times would be prohibitively wasteful.
struct TypeCounts {
    static constexpr int SIZE = 2048;
    std::array<std::uint64_t, SIZE> keys{};
    std::array<std::uint64_t, SIZE> values{};
    void increment(std::uint64_t key) {
        if (key == 0) return;
        int index = static_cast<int>((key ^ (key >> 32)) * 0x9E3779B9ULL) & (SIZE - 1);
        while (keys[index] != 0 && keys[index] != key) index = (index + 1) & (SIZE - 1);
        keys[index] = key; ++values[index];
    }
    void accept(const Perm& omega, int n) { increment(totally_unoriented_key(omega, n)); }
    std::uint64_t get(std::uint64_t key) const {
        int index = static_cast<int>((key ^ (key >> 32)) * 0x9E3779B9ULL) & (SIZE - 1);
        while (keys[index] != 0) {
            if (keys[index] == key) return values[index];
            index = (index + 1) & (SIZE - 1);
        }
        return 0;
    }

};

TypeCounts rotation_fixed(int n, int divisor, int shard_index, int shard_count) {
    if (divisor < 1 || divisor >= n || n % divisor != 0)
        throw std::invalid_argument("Rotation divisor must be a proper positive divisor of N");
    const int length = n / divisor;
    TypeCounts count;
    std::vector<int> order(divisor), quotient(divisor), phase(divisor);
    Perm omega{};
    order[0] = 0;
    std::uint64_t task = 0;
    auto phases = [&](auto&& self, int index, int sum) -> void {
        for (int t = 0; t < length; ++t) {
            phase[index] = t;
            if (index + 1 < divisor) self(self, index + 1, sum + t);
            else if (gcd(sum + t, length) == 1) {
                if (task++ % shard_count != static_cast<std::uint64_t>(shard_index)) continue;
                for (int v = 0; v < n; ++v) {
                    const int previous = v == 0 ? n - 1 : v - 1;
                    const int residue = previous % divisor;
                    omega[v] = quotient[residue]
                        + divisor * ((previous / divisor + phase[residue]) % length);
                }
                count.accept(omega, n);
            }
        }
    };
    auto orders = [&](auto&& self, int placed, unsigned used) -> void {
        if (placed == divisor) {
            for (int i = 0; i < divisor; ++i) quotient[order[i]] = order[(i + 1) % divisor];
            phases(phases, 0, 0);
            return;
        }
        for (int v = 1; v < divisor; ++v) if ((used & (1u << v)) == 0) {
            order[placed] = v; self(self, placed + 1, used | (1u << v));
        }
    };
    orders(orders, 1, 1);
    return count;
}

TypeCounts reflection_fixed(int n, int shift, int shard_index, int shard_count) {
    Perm reflection{}, q{}, omega{};
    std::array<std::array<int, 2>, MAX_N / 2> pairs{};
    int pair_count = 0, start = -1, end = -1;
    for (int v = 0; v < n; ++v) {
        reflection[v] = (shift - v % n + n) % n;
        const int h = (shift - 1 - v % n + n) % n;
        if (v < h) pairs[pair_count++] = {v, h};
        else if (v == h) { if (start < 0) start = v; else end = v; }
    }
    TypeCounts count;
    std::uint64_t task = 0;
    // Assign complete depth-two subtrees to shards.  Selecting only at a
    // terminal leaf would make every shard traverse all pair_count!*2^pair_count
    // alternating paths and discard almost all of them.
    const int frontier_depth = std::min(2, pair_count);
    auto paths = [&](auto&& self, unsigned used, int last, int first, bool selected) -> void {
        if (!selected && std::popcount(used) == frontier_depth) {
            if (task++ % shard_count != static_cast<std::uint64_t>(shard_index)) return;
            selected = true;
        }
        if (std::popcount(used) == pair_count) {
            if (first >= 0 && first > last) return;
            q[last] = end < 0 ? last : end;
            if (end >= 0) q[end] = last;
            for (int v = 0; v < n; ++v) omega[v] = q[reflection[v]];
            count.accept(omega, n);
            return;
        }
        for (int i = 0; i < pair_count; ++i) if ((used & (1u << i)) == 0) {
            for (int direction = 0; direction < 2; ++direction) {
                const int entry = pairs[i][direction], exit = pairs[i][1 - direction];
                q[entry] = last < 0 ? entry : last;
                if (last >= 0) q[last] = entry;
                self(self, used | (1u << i), exit, last < 0 ? entry : first, selected);
            }
        }
    };
    paths(paths, 0, start, -1, shard_count == 1);
    return count;
}
}

int main(int argc, char** argv) {
    try {
        std::string mode;
        std::vector<int> partition;
        int n = 21, divisor = 1, shift = 0, shard_index = 0, shard_count = 1;
        for (int i = 1; i < argc; ++i) {
            const std::string argument = argv[i];
            if (argument.starts_with("--mode=")) mode = argument.substr(7);
            else if (argument.starts_with("--partition=")) partition = parse_partition(argument.substr(12));
            else if (argument.starts_with("--n=")) n = std::stoi(argument.substr(4));
            else if (argument.starts_with("--divisor=")) divisor = std::stoi(argument.substr(10));
            else if (argument.starts_with("--shift=")) shift = std::stoi(argument.substr(8));
            else if (argument.starts_with("--shard-index=")) shard_index = std::stoi(argument.substr(14));
            else if (argument.starts_with("--shard-count=")) shard_count = std::stoi(argument.substr(14));
            else throw std::invalid_argument("Unknown argument: " + argument);
        }
        if (mode.empty() || n < 3 || n > MAX_N
                || (!partition.empty() && !valid_root_partition(partition, n)))
            throw std::invalid_argument("Expected mode, N=3..21, and an optional valid partition");
        if (shard_count < 1 || shard_index < 0 || shard_index >= shard_count)
            throw std::invalid_argument("Invalid shard");
        if (mode == "rotation" && (divisor < 1 || divisor >= n || n % divisor != 0))
            throw std::invalid_argument("Rotation divisor must be a proper positive divisor of N");
        if (mode == "reflection" && (shift < 0 || shift >= n))
            throw std::invalid_argument("Reflection shift must be in [0,N)");
        std::uint64_t result = 0;
        int result_divisor = 1;
        TypeCounts types;
        if (mode == "identity") {
            if (partition.empty()) throw std::invalid_argument("Identity mode requires a partition");
            IdentityCounter counter(partition, shard_index, shard_count);
            result = counter.run();
            result_divisor = counter.count_divisor();
        } else if (mode == "rotation") {
            types = rotation_fixed(n, divisor, shard_index, shard_count);
            if (!partition.empty()) result = types.get(partition_key(partition));
        } else if (mode == "reflection") {
            types = reflection_fixed(n, shift, shard_index, shard_count);
            if (!partition.empty()) result = types.get(partition_key(partition));
        } else throw std::invalid_argument("Mode must be identity, rotation, or reflection");
        std::cout << "{\"format\":\"td20-unoriented-burnside-term-v2\",\"mode\":\""
                  << mode << "\",\"n\":" << n;
        if (!partition.empty()) std::cout << ",\"partition\":" << json_partition(partition);
        std::cout << ",\"shard_index\":" << shard_index
                  << ",\"shard_count\":" << shard_count;
        if (mode == "rotation") std::cout << ",\"divisor\":" << divisor;
        if (mode == "reflection") std::cout << ",\"shift\":" << shift;
        if (!partition.empty() && mode == "identity")
            std::cout << ",\"count_numerator\":" << result
                      << ",\"count_divisor\":" << result_divisor;
        else if (!partition.empty()) std::cout << ",\"count\":" << result;
        else {
            std::cout << ",\"counts_by_key\":{";
            bool first = true;
            for (int i = 0; i < TypeCounts::SIZE; ++i) if (types.keys[i] != 0) {
                if (!first) std::cout << ',';
                first = false;
                std::cout << '\"' << types.keys[i] << "\":" << types.values[i];
            }
            std::cout << '}';
        }
        std::cout << "}\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
