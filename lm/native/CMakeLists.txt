# SPDX-License-Identifier: GPL-3.0-only
cmake_minimum_required(VERSION 3.22.1)
project(caret_lm C CXX)

# llama.cpp (MIT) runs Caret's typing model. Built from a checkout given with -DLLAMA_SRC=<path>,
# at the release scripts/build-lm.sh pins. Always optimized: unoptimized it is far too slow.
set(CMAKE_BUILD_TYPE Release)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# its fast matrix code needs half-float instructions 32-bit ARM phones do not all have
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)

if(ANDROID)
    add_library(caret_lm SHARED lm.cpp lm_jni.cpp)
    target_link_libraries(caret_lm PRIVATE llama log)
    # 16 KB pages, as Android 15 and later want
    target_link_options(caret_lm PRIVATE "-Wl,-z,max-page-size=16384")
else()
    add_executable(lm_test lm.cpp lm_test.cpp)
    target_link_libraries(lm_test PRIVATE llama)
endif()
