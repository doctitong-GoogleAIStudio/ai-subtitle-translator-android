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
