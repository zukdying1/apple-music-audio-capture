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
set(CMAKE_CXX_LINK_EXECUTABLE "${CMAKE_C_COMPILER} <FLAGS> <CMAKE_CXX_LINK_FLAGS> <LINK_FLAGS> <OBJECTS> -o <TARGET> <LINK_LIBRARIES>")
add_library(appledecryptor SHARED appledecryptor.cpp)

target_link_options(
    appledecryptor
    PRIVATE
    "-L${ANDROID_RUNTIME_LIB_DIR}/32"
    "-L${ANDROID_RUNTIME_LIB_DIR}"
)

find_library(log-lib log)

target_link_libraries(appledecryptor ${log-lib})
