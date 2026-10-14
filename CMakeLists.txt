# Forked from piper project

cmake_minimum_required(VERSION 3.26)

project(piper-jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
include(GNUInstallDirs)

# Apply patches to piper submodule
set(PIPER_SRC_DIR "${CMAKE_CURRENT_BINARY_DIR}/piper_patched/piper")

# Copy piper source to build directory to apply patches without modifying source tree
file(REMOVE_RECURSE "${CMAKE_CURRENT_BINARY_DIR}/piper_patched")
file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/src/main/native/piper" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/piper_patched" PATTERN ".git" EXCLUDE)

# Apply patch
execute_process(
    COMMAND patch -p1 -i "${CMAKE_CURRENT_SOURCE_DIR}/src/main/native/piper.patch"
    WORKING_DIRECTORY "${PIPER_SRC_DIR}"
    RESULT_VARIABLE patch_result
)
if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "Failed to apply patch to piper source")
endif()

# Extract Piper version
file(READ "${PIPER_SRC_DIR}/setup.py" setup_py_content)
string(REGEX MATCH "version=\"([0-9]+\\.[0-9]+\\.[0-9]+)\"" _ "${setup_py_content}")
if(NOT CMAKE_MATCH_1)
    message(FATAL_ERROR "Failed to extract piper version from setup.py.")
endif()
set(piper_version "\"${CMAKE_MATCH_1}\"")

# Dependency: espeak-ng
set(ESPEAKNG_BUILD_DIR ${CMAKE_BINARY_DIR}/espeak_ng)
set(ESPEAKNG_INSTALL_DIR ${CMAKE_BINARY_DIR}/espeak_ng-install)

if(WIN32)
    # Special handling for Windows
    set(ESPEAKNG_STATIC_LIB ${ESPEAKNG_INSTALL_DIR}/lib/espeak-ng.lib)
    set(UCD_STATIC_LIB ${ESPEAKNG_BUILD_DIR}/src/espeak_ng_external-build/src/ucd-tools/$<CONFIG>/ucd.lib)
else()
    set(ESPEAKNG_STATIC_LIB ${ESPEAKNG_INSTALL_DIR}/lib/libespeak-ng.a)
    set(UCD_STATIC_LIB ${ESPEAKNG_BUILD_DIR}/src/espeak_ng_external-build/src/ucd-tools/libucd.a)
endif()

ExternalProject_Add(espeak_ng_external
    GIT_REPOSITORY https://github.com/espeak-ng/espeak-ng.git
    GIT_TAG 212928b394a96e8fd2096616bfd54e17845c48f6  # 2025-Mar-22
    PREFIX ${ESPEAKNG_BUILD_DIR}
    CMAKE_ARGS
        -DCMAKE_INSTALL_PREFIX=${ESPEAKNG_INSTALL_DIR}
        -DBUILD_SHARED_LIBS:BOOL=OFF
        -DCMAKE_POSITION_INDEPENDENT_CODE:BOOL=ON
        -DUSE_ASYNC:BOOL=OFF
        -DUSE_MBROLA:BOOL=OFF
        -DUSE_LIBSONIC:BOOL=OFF
        -DUSE_LIBPCAUDIO:BOOL=OFF
        -DUSE_KLATT:BOOL=OFF
        -DUSE_SPEECHPLAYER:BOOL=OFF
        -DEXTRA_cmn:BOOL=ON
        -DEXTRA_ru:BOOL=ON
        # Need to explicitly add ucd include directory for CI
        "-DCMAKE_C_FLAGS=-D_FILE_OFFSET_BITS=64 -I${ESPEAKNG_BUILD_DIR}/src/espeak_ng_external/src/ucd-tools/src/include"
        "-DCMAKE_CXX_FLAGS=-D_FILE_OFFSET_BITS=64 -I${ESPEAKNG_BUILD_DIR}/src/espeak_ng_external/src/ucd-tools/src/include"
    BUILD_BYPRODUCTS
        ${ESPEAKNG_STATIC_LIB}
        ${UCD_STATIC_LIB}
    UPDATE_DISCONNECTED TRUE
)

# Dependency: ONNX Runtime
set(ONNXRUNTIME_VERSION "1.22.0")
if(WIN32)
    # Windows x86_64
    set(ONNXRUNTIME_PREFIX "onnxruntime-win-x64-${ONNXRUNTIME_VERSION}")
    set(ONNXRUNTIME_EXT "zip")
elseif(APPLE)
    if(CMAKE_SYSTEM_PROCESSOR STREQUAL x86_64)
        # macOS x86_64
        set(ONNXRUNTIME_PREFIX "onnxruntime-osx-x86_64-${ONNXRUNTIME_VERSION}")
    elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL arm64)
        # MacOS Apple Silicon
        set(ONNXRUNTIME_PREFIX "onnxruntime-osx-arm64-${ONNXRUNTIME_VERSION}")
    else()
        message(FATAL_ERROR "Unsupported architecture for onnxruntime")
    endif()
    set(ONNXRUNTIME_EXT "tgz")
else()
    if(CMAKE_SYSTEM_PROCESSOR STREQUAL x86_64)
        # Linux x86_64
        set(ONNXRUNTIME_PREFIX "onnxruntime-linux-x64-${ONNXRUNTIME_VERSION}")
    elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL aarch64)
        # Linux ARM 64-bit
        set(ONNXRUNTIME_PREFIX "onnxruntime-linux-aarch64-${ONNXRUNTIME_VERSION}")
    else()
        message(FATAL_ERROR "Unsupported architecture for onnxruntime")
    endif()
    set(ONNXRUNTIME_EXT "tgz")
endif()

set(ONNXRUNTIME_URL "https://github.com/microsoft/onnxruntime/releases/download/v${ONNXRUNTIME_VERSION}/${ONNXRUNTIME_PREFIX}.${ONNXRUNTIME_EXT}")
set(ONNXRUNTIME_EXTRACT_DIR "${CMAKE_CURRENT_BINARY_DIR}/onnxruntime")
set(ONNXRUNTIME_DIR "${ONNXRUNTIME_EXTRACT_DIR}/${ONNXRUNTIME_PREFIX}")

if(NOT EXISTS "${ONNXRUNTIME_DIR}")
    message(STATUS "Downloading ONNX Runtime: ${ONNXRUNTIME_URL}")

    file(DOWNLOAD
        "${ONNXRUNTIME_URL}"
        "${CMAKE_CURRENT_BINARY_DIR}/ort_pkg.${ONNXRUNTIME_EXT}"
        STATUS download_status
        LOG download_log
    )

    list(GET download_status 0 error_code)
    list(GET download_status 1 error_message)
    if(NOT error_code EQUAL 0)
        message(FATAL_ERROR "Failed to download ONNX Runtime from ${ONNXRUNTIME_URL}: ${error_message}\n${download_log}")
    endif()

    file(ARCHIVE_EXTRACT
        INPUT "${CMAKE_CURRENT_BINARY_DIR}/ort_pkg.${ONNXRUNTIME_EXT}"
        DESTINATION "${ONNXRUNTIME_EXTRACT_DIR}"
    )
endif()

# JNI & Compiler Setup
set(JAVA_AWT_LIBRARY NotNeeded)
set(JAVA_JVM_LIBRARY NotNeeded)
find_package(JNI REQUIRED)
if (JNI_FOUND)
    message(STATUS "JNI_INCLUDE_DIRS=${JNI_INCLUDE_DIRS}")
endif()

if(MSVC)
    # Force compiler to use UTF-8 for IPA constants
    add_compile_options("/utf-8")
elseif(NOT APPLE)
    # Linux flags
    string(APPEND CMAKE_CXX_FLAGS " -Wall -Wextra -Wl,-rpath,'$ORIGIN'")
    string(APPEND CMAKE_C_FLAGS " -Wall -Wextra")
endif()

# Main Target
add_library(piper-jni SHARED
    src/main/native/io_github_jvoiceproject_piperjni_internal_PiperBinding.cpp
    ${PIPER_SRC_DIR}/libpiper/src/piper.cpp
)

add_dependencies(piper-jni espeak_ng_external)

target_compile_definitions(piper-jni PUBLIC _PIPER_VERSION=${piper_version})
if(WIN32)
    target_compile_definitions(piper-jni PUBLIC ESPEAK_NG_STATIC LIBESPEAK_NG_EXPORT)
endif()

target_include_directories(piper-jni PUBLIC
    ${JNI_INCLUDE_DIRS}
    ${PIPER_SRC_DIR}/libpiper/include
    ${ESPEAKNG_INSTALL_DIR}/include
    ${ONNXRUNTIME_DIR}/include
)

target_link_directories(piper-jni PUBLIC
    ${ONNXRUNTIME_DIR}/lib
)

target_link_libraries(piper-jni
    ${ESPEAKNG_STATIC_LIB}
    ${UCD_STATIC_LIB}
    onnxruntime
    $<$<PLATFORM_ID:Windows>:User32.lib>
    $<$<NOT:$<PLATFORM_ID:Windows>>:pthread>
    $<$<NOT:$<PLATFORM_ID:Windows,Darwin>>:-static-libgcc -static-libstdc++>
)

# Asset Packaging (espeak-ng-data)
set(ESPEAKNG_DATA_SRC ${ESPEAKNG_INSTALL_DIR}/share/espeak-ng-data)
set(ESPEAKNG_DATA_ZIP ${CMAKE_CURRENT_BINARY_DIR}/espeak-ng-data.zip)
add_custom_command(
    OUTPUT ${ESPEAKNG_DATA_ZIP}
    COMMAND ${CMAKE_COMMAND} -E tar "cf" ${ESPEAKNG_DATA_ZIP} --format=zip .
    WORKING_DIRECTORY ${ESPEAKNG_DATA_SRC}
    DEPENDS espeak_ng_external
    COMMENT "Zipping espeak-ng-data"
)
add_custom_target(espeak-ng-data ALL DEPENDS ${ESPEAKNG_DATA_ZIP})

# Install
install(TARGETS piper-jni DESTINATION ${CMAKE_INSTALL_PREFIX})

# Install ONNX Runtime libs
install(
    DIRECTORY ${ONNXRUNTIME_DIR}/lib/
    DESTINATION ${CMAKE_INSTALL_PREFIX}
    FILES_MATCHING PATTERN "*.dll" PATTERN "*.so*" PATTERN "*.dylib"
)

# Install generated espeak-ng-data.zip
install(FILES ${ESPEAKNG_DATA_ZIP} DESTINATION ${CMAKE_INSTALL_PREFIX}/..)
