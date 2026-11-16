#include <cuda_runtime.h>
#include <jni.h>

#include <cstdio>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <stdexcept>
#include <vector>

#include "core/cuda_error.cuh"
#include "backtracking/slot_manager.cuh"
#include "backtracking/sorting_search.cuh"

static void throwRuntimeException(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_br_unb_cic_td20_base_GPUSortingSearch_initSlots(JNIEnv *env, jclass, jint totalSlots, jintArray javaDevices,
                                                     jlong queueBytesBudget) {
    try {
        if (javaDevices == nullptr) {
            throw std::invalid_argument("devices must not be null");
        }
        if (queueBytesBudget <= 0) {
            throw std::invalid_argument("queueBytesBudget must be positive");
        }
        const jsize devicesLength = env->GetArrayLength(javaDevices);
        std::vector<int> devices(static_cast<size_t>(devicesLength));
        if (devicesLength > 0) {
            std::vector<jint> javaDeviceValues(static_cast<size_t>(devicesLength));
            env->GetIntArrayRegion(javaDevices, 0, devicesLength, javaDeviceValues.data());
            for (jsize i = 0; i < devicesLength; ++i) {
                devices[static_cast<size_t>(i)] = static_cast<int>(javaDeviceValues[static_cast<size_t>(i)]);
            }
        }
        initGpuSortSlots(static_cast<int>(totalSlots), devices, static_cast<size_t>(queueBytesBudget));
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_br_unb_cic_td20_base_GPUSortingSearch_searchForSortingSeqWithLimits(JNIEnv *env, jclass,
                                                               jbyteArray javaBeta,
                                                               jbyteArray javaOmega,
                                                               jint maxMoves,
                                                               jboolean allowDiameterClosure,
                                                               jint maximumSearchDepth,
                                                               jlong maximumNanos,
                                                               jintArray javaDiameters,
                                                               jint slot) {
    try {
        clearCudaRuntimePoisoned();
        if (maximumNanos < 0) throw std::invalid_argument("Search allowance must not be negative");

        if (javaBeta == nullptr || javaOmega == nullptr) {
            throw std::invalid_argument("beta and omega must not be null");
        }
        const jsize n = env->GetArrayLength(javaBeta);
        if (env->GetArrayLength(javaOmega) != n) {
            throw std::invalid_argument("beta and omega must have equal lengths");
        }
        std::vector<short> beta(n);
        std::vector<short> omega(n);

        jbyte *betaPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(javaBeta, nullptr));
        if (betaPtr == nullptr) {
            throw std::runtime_error("failed to access beta array");
        }
        for (jsize i = 0; i < n; ++i) beta[i] = static_cast<short>(betaPtr[i] & 0xFF);
        env->ReleasePrimitiveArrayCritical(javaBeta, betaPtr, JNI_ABORT);

        jbyte *omegaPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(javaOmega, nullptr));
        if (omegaPtr == nullptr) {
            throw std::runtime_error("failed to access omega array");
        }
        for (jsize i = 0; i < n; ++i) omega[i] = static_cast<short>(omegaPtr[i] & 0xFF);
        env->ReleasePrimitiveArrayCritical(javaOmega, omegaPtr, JNI_ABORT);

        const int slotIndex = static_cast<int>(slot);
        const int slotDevice = getGpuSortSlotDevice(slotIndex);
        if (slotDevice < 0) {
            throw std::invalid_argument("invalid GPU sorting slot");
        }
        cudaStream_t rawStream = getGpuSortSlotStream(slotIndex);

        if (javaDiameters == nullptr || env->GetArrayLength(javaDiameters) != MAX_N + 1)
            throw std::invalid_argument("Invalid diameter table");
        std::vector<jint> rawDiameters(MAX_N + 1);
        env->GetIntArrayRegion(javaDiameters, 0, MAX_N + 1, rawDiameters.data());
        const std::vector<int> diameters(rawDiameters.begin(), rawDiameters.end());
        const auto result = performGpuSortingSearch(
                std::move(beta), std::move(omega), static_cast<int>(maxMoves),
                static_cast<bool>(allowDiameterClosure), static_cast<int>(maximumSearchDepth),
                static_cast<unsigned long long>(maximumNanos),
                diameters, rawStream, slotIndex);
        const auto &moves = result.moves;

        jclass intArrayClass = env->FindClass("[I");
        jobjectArray outer = env->NewObjectArray(static_cast<jsize>(moves.size() + 1), intArrayClass, nullptr);
        jint kind = result.kind;
        jintArray header = env->NewIntArray(1);
        env->SetIntArrayRegion(header, 0, 1, &kind);
        env->SetObjectArrayElement(outer, 0, header);
        env->DeleteLocalRef(header);
        for (jsize idx = 0; idx < static_cast<jsize>(moves.size()); ++idx) {
            jint move[3] = {moves[idx].firstIndex, moves[idx].secondIndex, moves[idx].thirdIndex};
            jintArray inner = env->NewIntArray(3);
            env->SetIntArrayRegion(inner, 0, 3, move);
            env->SetObjectArrayElement(outer, idx + 1, inner);
            env->DeleteLocalRef(inner);
        }
        env->DeleteLocalRef(intArrayClass);
        return outer;
    } catch (const GpuMemoryExhaustedException &e) {
        throwRuntimeException(env, e.what());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
    }
    return nullptr;
}
