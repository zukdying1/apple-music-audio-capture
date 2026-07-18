cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

project(appledecryptor CXX)

# Detect host OS for NDK path resolution
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(HOST_TAG "linux-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(HOST_TAG "darwin-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(HOST_TAG "windows-x86_64")
else()
    message(FATAL_ERROR "Unsupported host system: ${CMAKE_HOST_SYSTEM_NAME}")
endif()

set(ANDROID_RUNTIME_LIB_DIR "${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/sysroot/usr/lib/aarch64-linux-android")

# We use -DANDROID_STL=c++_static from Gradle.
# The NDK toolchain adds -isystem for headers and -lc++_static for linking,
# but the custom CMAKE_CXX_LINK_EXECUTABLE only affects executables (not SHARED).
# For safety, add the c++_static path explicitly.
set(CXX_STATIC_LIB "${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/sysroot/usr/lib/aarch64-linux-android/libc++_static.a")

add_library(appledecryptor SHARED appledecryptor.cpp)

target_link_options(
    appledecryptor
    PRIVATE
    "-L${ANDROID_RUNTIME_LIB_DIR}/32"
    "-L${ANDROID_RUNTIME_LIB_DIR}"
    "-Wl,--gc-sections"
)

find_library(log-lib log)

if(EXISTS "${CXX_STATIC_LIB}")
    target_link_libraries(appledecryptor ${log-lib} "${CXX_STATIC_LIB}")
else()
    # Fallback: rely on NDK toolchain -lc++_static via ANDROID_STL
    target_link_libraries(appledecryptor ${log-lib})
endif()