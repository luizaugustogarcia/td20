#pragma once

#include <cstddef>
#include <vector>

#include <cuda_runtime.h>

#include "core/common.cuh"

void allocateGpuSortingSearchSlot(int slot,
                                  int device,
                                  size_t queueBytesBudget,
                                  int slotsOnDevice,
                                  int slotOrdinalOnDevice);

struct GpuSortingResult {
    int kind = 0; // 0: miss, 1: complete witness, 2: known-diameter closure
    std::vector<Move> moves;
};

GpuSortingResult performGpuSortingSearch(std::vector<short> beta,
                                          std::vector<short> omega,
                                          int maximumDepth,
                                          bool allowDiameterClosure,
                                          const std::vector<int> &knownDiameters,
                                          cudaStream_t stream,
                                          int slot,
                                          int maxWarps = -1);

void destroyGpuSortingSearchSlots();
