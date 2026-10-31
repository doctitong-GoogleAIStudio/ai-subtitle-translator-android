cmake_minimum_required(VERSION 3.22)
project(subtrans_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Speech recognition is always built optimised: an -O0 whisper.cpp is ~20x slower.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# whisper.cpp / ggml options: CPU only, no examples, shared libraries so ggml can load the
# CPU backend that matches the phone's instruction set at run time.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # android_armv8.0_1 ... android_armv9.2_2: dotprod / fp16 / i8mm / SVE variants.
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
elseif (ANDROID_ABI STREQUAL "x86_64")
    # Emulators and Chromebooks: SSE4.2 ... AVX-512 variants.
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    # 32-bit ABIs: one baseline CPU backend (NEON on armeabi-v7a, SSE on x86).
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_AVX OFF CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_F16C OFF CACHE BOOL "" FORCE)
    set(GGML_AVX512 OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR} whisper.cpp)

add_library(subtrans_jni SHARED subtrans_jni.cpp)
target_include_directories(subtrans_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(subtrans_jni whisper ggml ggml-base log android)

# Text translation (M2M100) with CTranslate2: Ruy is its only CPU backend here (int8 and
# float GEMM), no MKL / OpenMP. Kept in its own JNI library so speech recognition still
# works where it cannot load. CTranslate2's CPU kernels need a 64-bit ABI; 32-bit phones
# keep Whisper's own translate task.
if (NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    return()
endif()
set(CT2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ctranslate2)
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(RUY_MINIMAL_BUILD ON CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_MOCK_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_PKG_CONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory(${CT2_DIR} ctranslate2)
target_compile_options(ctranslate2 PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/ct2_android_compat.h)

add_library(subtrans_mt SHARED subtrans_mt.cpp)
target_include_directories(subtrans_mt PRIVATE ${CT2_DIR}/src)
target_link_libraries(subtrans_mt ctranslate2 log)
