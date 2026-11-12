// Count budget-valid support-clearing closures among generated joint residuals.
// Build: c++ -O3 -std=c++20 tools/audit/count_joint_clearings.cpp -o /tmp/td20-joint-clearings
// The local classification and its cited support-clearing theorem are recorded
// in docs/td20-reduction-count.tex; local powers are checked by verify_local_cycles.py.
// These are conditional structural closures, not emitted sorting certificates.
#include <algorithm>
#include <array>
#include <charconv>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
constexpr int N = 21;
using Perm = std::array<int, N>;
constexpr std::array<int, 4> LENGTHS{9, 11, 13, 17};

std::uint64_t unsigned_number(std::string_view value) {
    if (value.empty() || value.front() < '0' || value.front() > '9') {
        throw std::runtime_error("Expected an unsigned decimal integer");
    }
    std::uint64_t result = 0;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), result);
    if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size()) {
        throw std::runtime_error("Malformed or overflowing unsigned integer");
    }
    return result;
}

Perm parse_permutation(std::string_view line) {
    Perm omega{};
    const char* next = line.data();
    const char* end = next + line.size();
    unsigned values = 0;
    for (int x = 0; x < N; ++x) {
        if (next == end || *next < '0' || *next > '9') {
            throw std::runtime_error("Expected exactly 21 permutation images");
        }
        const auto parsed = std::from_chars(next, end, omega[x]);
        if (parsed.ec != std::errc{} || omega[x] < 0 || omega[x] >= N
                || (values & (1u << omega[x])) || omega[x] == x) {
            throw std::runtime_error("Expected a fixed-point-free permutation on 21 symbols");
        }
        values |= 1u << omega[x];
        next = parsed.ptr;
        if (x + 1 < N) {
            if (next == end || *next != ' ') {
                throw std::runtime_error("Expected a space between permutation images");
            }
            while (next != end && *next == ' ') ++next;
        }
    }
    if (next != end) throw std::runtime_error("Extra data after permutation images");
    unsigned seen = 0;
    int x = 0;
    for (int count = 0; count < N; ++count) {
        if (seen & (1u << x)) throw std::runtime_error("The target is not a full cycle");
        seen |= 1u << x;
        x = omega[(x + 1) % N];
    }
    if (x != 0) throw std::runtime_error("The target is not a full cycle");
    return omega;
}

bool power_on_support(const Perm& omega, const int* support, int length, int exponent) {
    for (int rank = 0; rank < length; ++rank) {
        if (omega[support[rank]] != support[(rank + exponent) % length]) return false;
    }
    return true;
}

bool admitted_power(const Perm& omega, const int* support, int length) {
    // Only these classified failures have the cited clearing alternative
    // within the root budget: (k+1)/2 + TD(20-k) = 11.
    // Length 7 exceeds that budget. The length-9 power 5 and length 15
    // have no support-clearing conclusion used by this audit.
    switch (length) {
        case 9: return power_on_support(omega, support, length, 7);
        case 11: return power_on_support(omega, support, length, 6);
        case 13: return power_on_support(omega, support, length, 7)
                || power_on_support(omega, support, length, 10)
                || power_on_support(omega, support, length, 11);
        case 17: return power_on_support(omega, support, length, 9)
                || power_on_support(omega, support, length, 13)
                || power_on_support(omega, support, length, 15);
        default: return false;
    }
}

int closing_length(const Perm& omega) {
    unsigned seen = 0;
    bool oriented = false;
    int chosen = 0;
    for (int first = 0; first < N; ++first) {
        if (seen & (1u << first)) continue;
        std::array<int, N> support{};
        int x = first, length = 0, increases = 0;
        do {
            seen |= 1u << x;
            support[length++] = x;
            increases += omega[x] > x;
            x = omega[x];
        } while (x != first);
        if (length >= 3 && increases != 1) oriented = true;
        if ((chosen == 0 || length < chosen)
                && std::find(LENGTHS.begin(), LENGTHS.end(), length) != LENGTHS.end()) {
            std::sort(support.begin(), support.begin() + length);
            if (admitted_power(omega, support.data(), length)) chosen = length;
        }
    }
    if (!oriented) throw std::runtime_error("Totally unoriented root in an oriented-only input");
    return chosen;
}

std::string json_string(const std::string& input) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result = "\"";
    for (const unsigned char ch : input) {
        if (ch == '\\' || ch == '"') { result += '\\'; result += ch; }
        else if (ch < 32) {
            result += "\\u00"; result += digits[ch >> 4]; result += digits[ch & 15];
        } else result += ch;
    }
    result += '"';
    return result;
}

void count_file(const std::string& path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("Cannot open input: " + path);
    std::string line;
    if (!std::getline(input, line) || line != "TD20-JOINT2-RESIDUALS 1 N=21 scope=oriented") {
        throw std::runtime_error("Missing or incompatible residual header: " + path);
    }
    std::uint64_t scanned = 0, closed = 0;
    std::array<std::uint64_t, 4> by_length{};
    bool footer = false;
    while (std::getline(input, line)) {
        if (footer) throw std::runtime_error("Data after terminal COUNT: " + path);
        if (line.starts_with("COUNT ")) {
            if (unsigned_number(std::string_view(line).substr(6)) != scanned) {
                throw std::runtime_error("Terminal COUNT differs from scanned records: " + path);
            }
            footer = true;
            continue;
        }
        const int length = closing_length(parse_permutation(line));
        ++scanned;
        if (length != 0) {
            ++closed;
            ++by_length[std::find(LENGTHS.begin(), LENGTHS.end(), length) - LENGTHS.begin()];
        }
    }
    if (input.bad() || !footer) throw std::runtime_error("Truncated or unreadable residual input: " + path);
    std::cout << "{\"format\":\"td20-joint2-support-clearing-count-v1\",\"input\":"
              << json_string(path) << ",\"domain\":21,\"scanned\":" << scanned
              << ",\"support_clearing\":" << closed << ",\"remaining\":" << scanned - closed
              << ",\"least_closing_length\":{";
    for (std::size_t i = 0; i < LENGTHS.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << '"' << LENGTHS[i] << "\":" << by_length[i];
    }
    std::cout << "}}" << std::endl;
    if (!std::cout) throw std::runtime_error("Cannot write count report");
}

void self_test() {
    const std::vector<std::pair<int, std::vector<int>>> classified{
        {5, {3}}, {7, {4}}, {9, {5, 7}}, {11, {6}}, {13, {7, 10, 11}},
        {15, {8}}, {17, {9, 13, 15}}
    };
    unsigned tested = 0;
    for (const auto& [length, exponents] : classified) {
        for (const int exponent : exponents) {
            Perm omega{};
            std::array<int, N> support{};
            for (int x = 0; x < N; ++x) { omega[x] = x; support[x] = x; }
            for (int x = 0; x < length; ++x) omega[x] = (x + exponent) % length;
            const bool expected = length != 5 && length != 7 && length != 15
                    && !(length == 9 && exponent == 5);
            if (admitted_power(omega, support.data(), length) != expected) {
                throw std::runtime_error("Support-clearing power fixture failed");
            }
            // Independently test every applicable local transposition: none
            // of the classified candidates has a qualifying local move.
            for (int a = 0; a < length - 2; ++a) for (int b = a + 1; b < length - 1; ++b)
                    for (int c = b + 1; c < length; ++c) {
                Perm q = omega; q[a] = omega[c]; q[b] = omega[a]; q[c] = omega[b];
                unsigned seen = 0; int odd = 0; bool fixed = false;
                for (int x = 0; x < length; ++x) {
                    fixed |= q[x] == x;
                    if (seen & (1u << x)) continue;
                    int y = x, size = 0;
                    do { seen |= 1u << y; ++size; y = q[y]; } while (y != x);
                    odd += size % 2;
                }
                if (fixed && odd == 3) throw std::runtime_error("Classified power admits a qualifying move");
            }
            ++tested;
        }
    }
    std::cout << "{\"format\":\"td20-joint2-support-clearing-self-test-v1\",\"local_candidates\":"
              << tested << ",\"agrees\":true}" << std::endl;
}
}

int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::string_view(argv[1]) == "--self-test") { self_test(); return 0; }
        if (argc < 2) throw std::invalid_argument("Provide one or more native residual files, or --self-test");
        for (int i = 1; i < argc; ++i) count_file(argv[i]);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
