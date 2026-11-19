cmake_minimum_required(VERSION 3.24)
project(td20_native LANGUAGES CXX CUDA)

find_package(CUDAToolkit REQUIRED)

set(TD20_CUDA_ARCHITECTURES "89-real;120-real;120-virtual"
    CACHE STRING "CUDA architectures (default: SM 89 and SM 120 native code, SM 120 forward PTX)")
if(TD20_CUDA_ARCHITECTURES MATCHES "(^|;)89(-real|-virtual)?(;|$)"
        AND CMAKE_CUDA_COMPILER_VERSION VERSION_LESS 11.8)
    message(FATAL_ERROR "CUDA architecture SM 89 requires CUDA Toolkit 11.8 or newer")
endif()
if(TD20_CUDA_ARCHITECTURES MATCHES "(^|;)120(-real|-virtual)?(;|$)"
        AND CMAKE_CUDA_COMPILER_VERSION VERSION_LESS 12.8)
    message(FATAL_ERROR "CUDA architecture SM 120 requires CUDA Toolkit 12.8 or newer")
endif()
set(CMAKE_CUDA_ARCHITECTURES ${TD20_CUDA_ARCHITECTURES})
message(STATUS "CUDA architectures: ${CMAKE_CUDA_ARCHITECTURES}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_RUNTIME_LIBRARY Shared)

set(TD20_NATIVE_MAX_N 21)
set(TD20_NATIVE_MAX_DEPTH 11)

# JNI bridge for the TD(20) exact bounded search.

find_package(JNI)

if(JNI_FOUND)
    add_library(td20_jni SHARED
        jni/jni_sorter.cu
        backtracking/slot_manager.cu
        backtracking/sorting_search.cu
    )

    target_include_directories(td20_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JNI_INCLUDE_DIRS}
    )
    target_link_libraries(td20_jni PRIVATE CUDA::cudart ${JNI_LIBRARIES})

    set_target_properties(td20_jni PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CUDA_SEPARABLE_COMPILATION ON
        CUDA_RESOLVE_DEVICE_SYMBOLS ON
    )

    target_compile_definitions(td20_jni PRIVATE MAX_N=${TD20_NATIVE_MAX_N} MAX_DEPTH=${TD20_NATIVE_MAX_DEPTH})

    target_compile_options(td20_jni PRIVATE
        $<$<COMPILE_LANGUAGE:CUDA>:-Xptxas=-v>
        $<$<CONFIG:Debug>:-G>
        $<$<CONFIG:RelWithDebInfo>:-lineinfo>
    )

    install(TARGETS td20_jni DESTINATION lib)
endif()

option(TD20_BUILD_NATIVE_TESTS "Build CUDA-dependent native regression tests (Linux linker)" OFF)
option(TD20_BUILD_INVERSE_BENCHMARK "Build isolated experimental CUDA inverse benchmark" OFF)
if(TD20_BUILD_INVERSE_BENCHMARK AND JNI_FOUND)
    add_library(td20_inverse_benchmark SHARED experimental/inverse_benchmark.cu)
    target_include_directories(td20_inverse_benchmark PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(td20_inverse_benchmark PRIVATE CUDA::cudart)
    target_compile_options(td20_inverse_benchmark PRIVATE $<$<COMPILE_LANGUAGE:CUDA>:-Xptxas=-v>)
endif()
if(TD20_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(td20_slot_memory_test
        tests/slot_memory_test.cpp
        backtracking/slot_manager.cu
        backtracking/sorting_search.cu
    )
    target_include_directories(td20_slot_memory_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(td20_slot_memory_test PRIVATE CUDA::cudart)
    target_compile_definitions(td20_slot_memory_test PRIVATE MAX_N=${TD20_NATIVE_MAX_N} MAX_DEPTH=${TD20_NATIVE_MAX_DEPTH})
    set_target_properties(td20_slot_memory_test PROPERTIES
        CUDA_SEPARABLE_COMPILATION ON
        CUDA_RESOLVE_DEVICE_SYMBOLS ON
    )
    target_link_options(td20_slot_memory_test PRIVATE
        "LINKER:--wrap=cudaMemGetInfo" "LINKER:--wrap=cudaMalloc")
    add_test(NAME slot_memory_preflight COMMAND td20_slot_memory_test)
endif()
