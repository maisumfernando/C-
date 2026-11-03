cmake_minimum_required(VERSION 3.22)
project(escriba LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party ABSOLUTE)
set(LLAMA_DIR   ${THIRD_PARTY}/llama.cpp)
set(WHISPER_DIR ${THIRD_PARTY}/whisper.cpp)

if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt OR NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "Submodules missing. Run: git submodule update --init --recursive")
endif()

# ---------------------------------------------------------------------------
# ggml + llama.cpp
#
# A single ggml build is shared by both engines. On arm64 ggml is built with
# one CPU backend per micro-architecture level (armv8.0 ... armv9.2 + SME) and
# the best one for the phone is picked at runtime, so the same APK is both
# compatible with older phones and fully optimised on flagships.
# ---------------------------------------------------------------------------
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON  CACHE BOOL "" FORCE)
    set(GGML_OPENMP       ON  CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} build-llama)

# ---------------------------------------------------------------------------
# Escriba engine: whisper.cpp (speech) + llama.cpp (text polishing) + JNI
# whisper.cpp is compiled against the same ggml as llama.cpp.
# ---------------------------------------------------------------------------
add_library(escriba SHARED
    jni_onload.cpp
    jni_whisper.cpp
    jni_llm.cpp
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
)

target_include_directories(escriba PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
)

file(STRINGS ${WHISPER_DIR}/CMakeLists.txt _wv REGEX "^set\\(WHISPER_VERSION_(MAJOR|MINOR|PATCH) [0-9]+\\)")
string(REGEX REPLACE "[^0-9;]" "" _wv "${_wv}")
string(REPLACE ";" "." WHISPER_VERSION "${_wv}")
message(STATUS "whisper.cpp version: ${WHISPER_VERSION}")

target_compile_definitions(escriba PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_compile_options(escriba PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -Wno-deprecated-declarations)

target_link_libraries(escriba PRIVATE llama llama-common ggml ggml-base android log)
target_link_options(escriba PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
