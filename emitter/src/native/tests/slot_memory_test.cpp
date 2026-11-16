#include <cuda_runtime.h>

#include <iostream>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>

#include "backtracking/slot_manager.cuh"
#include "backtracking/sorting_search.cuh"
#include "core/cuda_error.cuh"

namespace {
    std::optional<size_t> reportedFreeBytes;
    size_t allocationCalls = 0;
    size_t allocatedBytes = 0;

    void require(const bool condition, const char *message) {
        if (!condition) throw std::runtime_error(message);
    }

    size_t plannedBytes(const int slots, const size_t queueBytes) {
        size_t bytes = 0;
        for (int ordinal = 0; ordinal < slots; ++ordinal)
            bytes += gpuSortingSearchSlotBytes(0, queueBytes, slots, ordinal);
        return bytes;
    }

    void rejectBeforeAllocation(const int slots, const size_t queueBytes, const size_t freeBytes) {
        require(plannedBytes(slots, queueBytes) > freeBytes, "Test must exercise an insufficient budget");
        reportedFreeBytes = freeBytes;
        allocationCalls = allocatedBytes = 0;
        bool rejected = false;
        try {
            initGpuSortSlots(slots, {0}, queueBytes);
        } catch (const GpuMemoryExhaustedException &error) {
            rejected = true;
            require(std::string(error.what()).find("state caches and metadata") != std::string::npos,
                    "Preflight diagnostic must identify the complete memory requirement");
        }
        reportedFreeBytes.reset();
        require(rejected, "Insufficient slot memory passed preflight");
        require(allocationCalls == 0, "Preflight rejection must precede every device-buffer allocation");
    }

    void allocateAtExactBoundary(const int slots, const size_t queueBytes) {
        const size_t expected = plannedBytes(slots, queueBytes);
        reportedFreeBytes = expected;
        allocationCalls = allocatedBytes = 0;
        initGpuSortSlots(slots, {0}, queueBytes);
        reportedFreeBytes.reset();
        require(allocationCalls > 0, "Successful initialization must allocate device buffers");
        require(allocatedBytes == expected, "Preflight must equal the actual cudaMalloc byte total");
        destroyGpuSortSlots();
    }
}

extern "C" cudaError_t __real_cudaMemGetInfo(size_t *freeBytes, size_t *totalBytes);
extern "C" cudaError_t __wrap_cudaMemGetInfo(size_t *freeBytes, size_t *totalBytes) {
    if (!reportedFreeBytes) return __real_cudaMemGetInfo(freeBytes, totalBytes);
    *freeBytes = *reportedFreeBytes;
    *totalBytes = *reportedFreeBytes;
    return cudaSuccess;
}

extern "C" cudaError_t __real_cudaMalloc(void **pointer, size_t bytes);
extern "C" cudaError_t __wrap_cudaMalloc(void **pointer, size_t bytes) {
    ++allocationCalls;
    const auto result = __real_cudaMalloc(pointer, bytes);
    if (result == cudaSuccess) allocatedBytes += bytes;
    return result;
}

int main() {
    try {
        CHECK_CUDA(cudaSetDevice(0));
        constexpr size_t MiB = size_t{1} << 20;
        rejectBeforeAllocation(2, 32 * MiB, 64 * MiB);
        // Both runtime profiles need caches and metadata beyond their queues.
        rejectBeforeAllocation(8, 768 * MiB, 8 * 768 * MiB);
        rejectBeforeAllocation(8, 768 * MiB, plannedBytes(8, 768 * MiB) - 1);
        // Retain the larger memory-budget regression without allocating 28 GiB.
        rejectBeforeAllocation(32, 896 * MiB, 32 * 896 * MiB);
        rejectBeforeAllocation(3, 32 * MiB, plannedBytes(3, 32 * MiB) - 1);
        allocateAtExactBoundary(3, 32 * MiB);
        // Minimum queue capacities can exceed a tiny configured queue budget.
        allocateAtExactBoundary(2, 1);
        allocationCalls = allocatedBytes = 0;
        bool rejected = false;
        try {
            initGpuSortSlots(2, {0}, std::numeric_limits<size_t>::max());
        } catch (const std::invalid_argument &) { rejected = true; }
        require(rejected && allocationCalls == 0, "Oversized capacities must fail before allocation");
        std::cout << "Slot memory preflight: queue-only and one-byte-short budgets rejected; "
                     "exact allocations and tiny-queue minimums agree; oversized capacity rejected\n";
        return 0;
    } catch (const std::exception &error) {
        reportedFreeBytes.reset();
        destroyGpuSortSlots();
        std::cerr << error.what() << '\n';
        return 1;
    }
}
