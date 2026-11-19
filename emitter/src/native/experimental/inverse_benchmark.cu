#include <cuda_runtime.h>
#include <jni.h>
#include <cstdint>
#include <stdexcept>
#include <vector>

// Experimental only. The production emitter and checker do not call this.
namespace {
constexpr int capacity = 21, stride = 17;
__host__ __device__ int choose(int n, int k) {
    int result = 1;
    for (int i = 1; i <= k; ++i) result = result * (n - i + 1) / i;
    return result;
}

__device__ void combination(int ordinal, int first, int limit, int count, int *values) {
    for (int i = 0; i < count; ++i) {
        for (int value = first; value < limit; ++value) {
            int ways = choose(limit - value - 1, count - i - 1);
            if (ordinal < ways) { values[i] = value; first = value + 1; break; }
            ordinal -= ways;
        }
    }
}

__device__ void swap(int &a, int &b) { int t = a; a = b; b = t; }
__device__ void order(int &a, int &b, int &c, const int *positions) {
    if (positions[a] > positions[b]) swap(a, b);
    if (positions[b] > positions[c]) swap(b, c);
    if (positions[a] > positions[b]) swap(a, b);
}
__device__ int transform(int position, int shift, bool reflected, int n) {
    int result = reflected ? -shift - position : position - shift;
    if (result < 0) result += n;
    return result < 0 ? result + n : result;
}

// The same fixed-content convention and twisted reflection as the Java
// canonicalizer. Complete byte-for-byte multisets are checked by the benchmark.
__device__ void canonical(const int *omega, const int *beta, int n, unsigned char *key) {
    int positions[capacity], inverse[capacity], labels[capacity], ranks[capacity];
    int sizes[capacity], types[capacity], bases[2 * capacity], counts[2 * capacity];
    int first[capacity], origins[capacity], current[capacity], least[capacity];
    for (int i = 0; i < n; ++i) { positions[beta[i]] = i; inverse[omega[i]] = i; }
    for (int i = 0; i < 2 * n; ++i) counts[i] = 0;
    int visited = 0;
    for (int start = 0; start < n; ++start) {
        if (visited & (1 << start)) continue;
        int x = start, size = 0, increasing = 0;
        do {
            visited |= 1 << x; labels[x] = start; ranks[x] = size++;
            int next = omega[x];
            if (positions[next] > positions[x]) ++increasing;
            x = next;
        } while (x != start);
        sizes[start] = size;
        int type = 2 * (n - size) + (size >= 3 && increasing != 1 ? 1 : 0);
        types[start] = type; ++counts[type];
    }
    int next = 0;
    for (int type = 0; type < 2 * n; ++type) {
        bases[type] = next;
        next += counts[type] * ((type & 1) ? n - type / 2 : 1);
    }
    int leastShift = 0;
    bool leastReflected = false, hasLeast = false;
    for (int shift = 0; shift < n; ++shift) {
        for (int reflection = 0; reflection < 2; ++reflection) {
            bool reflected = reflection != 0;
            for (int i = 0; i < 2 * n; ++i) counts[i] = 0;
            for (int i = 0; i < n; ++i) first[i] = -1;
            int comparison = hasLeast ? 0 : -1;
            int position = reflected ? (n - shift) % n : shift;
            for (int offset = 0; offset < n; ++offset) {
                int symbol = beta[position], label = labels[symbol], type = types[label];
                bool oriented = (type & 1) != 0;
                if (first[label] < 0) {
                    first[label] = bases[type] + counts[type]++ * (oriented ? sizes[label] : 1);
                    origins[label] = ranks[symbol];
                }
                int value = first[label];
                if (oriented) {
                    int difference = reflected ? origins[label] - ranks[symbol] : ranks[symbol] - origins[label];
                    value += difference < 0 ? difference + sizes[label] : difference;
                }
                current[offset] = value;
                if (comparison == 0) {
                    comparison = value < least[offset] ? -1 : value > least[offset] ? 1 : 0;
                    if (comparison > 0) break;
                }
                position = reflected ? (position == 0 ? n - 1 : position - 1)
                    : (position + 1 == n ? 0 : position + 1);
            }
            if (comparison < 0) {
                for (int i = 0; i < n; ++i) least[i] = current[i];
                leastShift = shift; leastReflected = reflected; hasLeast = true;
            }
        }
    }
    for (int i = 0; i < n; ++i) {
        int symbol = beta[i];
        int imagePosition = positions[leastReflected ? inverse[symbol] : omega[symbol]];
        int position = transform(i, leastShift, leastReflected, n);
        int image = transform(imagePosition, leastShift, leastReflected, n);
        int bit = 5 * position, index = 3 + (bit >> 3), remainder = bit & 7;
        if (remainder <= 3) key[index] |= image << (3 - remainder);
        else { key[index] |= image >> (remainder - 3); key[index + 1] |= image << (11 - remainder); }
    }
}

__global__ void expand(const unsigned char *inputs, int m, int gain, int budget,
                       int candidatesPerSource, int totalCandidates, unsigned char *output, int *failure) {
    int ordinal = blockIdx.x * blockDim.x + threadIdx.x;
    if (ordinal >= totalCandidates) return;
    int n = m + gain, source = ordinal / candidatesPerSource;
    int candidate = ordinal % candidatesPerSource;
    int endpointCandidates = candidatesPerSource / 2;
    bool swapped = candidate >= endpointCandidates;
    candidate %= endpointCandidates;
    int supportCount = choose(m, 3 - gain);
    int placement = candidate / supportCount, support = candidate % supportCount;
    const unsigned char *input = inputs + 3 * m * source;
    int original[capacity], omega[capacity], expanded[capacity], next[capacity], positions[capacity];
    int beta[capacity], target[capacity], inverse[capacity], restored[3], old[3];
    for (int i = 0; i < m; ++i) {
        if (swapped) original[input[i]] = i;
        else original[i] = input[i];
    }
    for (int i = m; i < n; ++i) original[i] = i;
    combination(placement, 1, n, gain, restored);
    combination(support, 0, m, 3 - gain, old);
    int oldPosition = 0, inserted = 0;
    for (int i = 0; i < n; ++i) {
        int symbol = inserted < gain && restored[inserted] == i ? m + inserted++
            : input[(swapped ? 2 : 1) * m + oldPosition++];
        expanded[i] = symbol; positions[symbol] = i; omega[i] = original[i];
    }
    for (int i = 0; i < n; ++i) next[expanded[i]] = expanded[i + 1 == n ? 0 : i + 1];
    int a, b, c;
    if (gain == 3) { a = m; b = m + 1; c = m + 2; }
    else if (gain == 2) { a = m; b = m + 1; c = old[0]; }
    else if (gain == 1) { a = m; b = old[0]; c = old[1]; }
    else { a = old[0]; b = old[1]; c = old[2]; }
    order(a, b, c, positions);
    omega[a] = original[c]; omega[b] = original[a]; omega[c] = original[b];
    if (omega[a] == a || omega[b] == b || omega[c] == c) return;
    int x = expanded[0], visited = 0;
    for (int i = 0; i < n; ++i) {
        if (visited & (1 << x)) { atomicExch(failure, 1); return; }
        visited |= 1 << x; beta[i] = x; positions[x] = i;
        int edge = next[x]; x = edge == a ? b : edge == b ? c : edge == c ? a : edge;
    }
    if (x != expanded[0]) { atomicExch(failure, 2); return; }
    int fa = a, fb = b, fc = c;
    order(fa, fb, fc, positions);
    int actualGain = (omega[fc] == fa) + (omega[fa] == fb) + (omega[fb] == fc);
    if (actualGain != gain) return;
    if (omega[fc] != original[fa] || omega[fa] != original[fb] || omega[fb] != original[fc]) {
        atomicExch(failure, 3); return;
    }
    for (int i = 0; i < n; ++i) {
        int edge = beta[i + 1 == n ? 0 : i + 1];
        int replay = edge == fa ? fb : edge == fb ? fc : edge == fc ? fa : edge;
        if (replay != next[beta[i]]) { atomicExch(failure, 4); return; }
    }
    if (swapped) {
        x = 0; visited = 0;
        for (int i = 0; i < n; ++i) {
            if (visited & (1 << x)) { atomicExch(failure, 5); return; }
            visited |= 1 << x; target[i] = x; x = original[next[x]];
            inverse[omega[i]] = i;
        }
        if (x != 0) { atomicExch(failure, 6); return; }
    }
    unsigned char *key = output + static_cast<size_t>(ordinal) * stride;
    canonical(swapped ? inverse : omega, swapped ? target : beta, n, key);
    key[0] = 3; key[1] = budget; key[2] = n;
}

void checked(cudaError_t status) { if (status != cudaSuccess) throw std::runtime_error(cudaGetErrorString(status)); }
struct Buffer {
    void *data = nullptr;
    explicit Buffer(size_t bytes) { checked(cudaMalloc(&data, bytes)); }
    ~Buffer() { if (data) cudaFree(data); }
};
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_br_unb_cic_td20_experimental_GpuInverseReconstruction_expandNative(
    JNIEnv *env, jclass, jbyteArray packed, jint m, jint gain, jint budget, jint outputBytes) {
    try {
        if (!packed || m < 3 || m > capacity || gain < 0 || gain > 3 || gain > capacity - m || budget < 1 || budget > 11)
            throw std::invalid_argument("Invalid experimental inverse inputs");
        int inputBytes = env->GetArrayLength(packed);
        if (!inputBytes || inputBytes % (3 * m)) throw std::invalid_argument("Incomplete GPU inverse input");
        int perSource = 2 * choose(m + gain - 1, gain) * choose(m, 3 - gain);
        int64_t total = static_cast<int64_t>(inputBytes / (3 * m)) * perSource;
        if (outputBytes <= 0 || outputBytes > (256 << 20) || total * stride != outputBytes)
            throw std::invalid_argument("GPU inverse output capacity mismatch");
        std::vector<unsigned char> input(inputBytes), output(outputBytes);
        env->GetByteArrayRegion(packed, 0, inputBytes, reinterpret_cast<jbyte *>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        Buffer deviceInput(inputBytes), deviceOutput(outputBytes), deviceFailure(sizeof(int));
        checked(cudaMemcpy(deviceInput.data, input.data(), inputBytes, cudaMemcpyHostToDevice));
        checked(cudaMemset(deviceOutput.data, 0, outputBytes)); checked(cudaMemset(deviceFailure.data, 0, sizeof(int)));
        expand<<<(total + 127) / 128, 128>>>(static_cast<unsigned char *>(deviceInput.data), m, gain, budget,
            perSource, static_cast<int>(total), static_cast<unsigned char *>(deviceOutput.data), static_cast<int *>(deviceFailure.data));
        checked(cudaGetLastError());
        int failure = 0;
        checked(cudaMemcpy(&failure, deviceFailure.data, sizeof(int), cudaMemcpyDeviceToHost));
        if (failure) throw std::runtime_error("GPU inverse failed exact forward replay");
        checked(cudaMemcpy(output.data(), deviceOutput.data, outputBytes, cudaMemcpyDeviceToHost));
        jbyteArray result = env->NewByteArray(outputBytes);
        if (!result) return nullptr;
        env->SetByteArrayRegion(result, 0, outputBytes, reinterpret_cast<jbyte *>(output.data()));
        return result;
    } catch (const std::exception &error) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) { env->ThrowNew(type, error.what()); env->DeleteLocalRef(type); }
        return nullptr;
    }
}
