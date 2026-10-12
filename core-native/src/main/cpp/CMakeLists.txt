cmake_minimum_required(VERSION 3.23)
# The version is the Mumble protocol version this client speaks; mumble-core reports it.
project(mumblevideo_client VERSION 1.7.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# libopus, built from the release tarball. The hash pins exactly what goes into the APK.
include(FetchContent)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    opus
    URL https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(opus)

# mumble-core (vendored by tools/sync-core.sh) and what it links: OpenSSL libcrypto and Protobuf lite.
include(NativeDeps.cmake)
list(APPEND CMAKE_FIND_ROOT_PATH "${MV_DEPS_PREFIX}")
set(OPENSSL_ROOT_DIR "${MV_DEPS_PREFIX}")
set(OPENSSL_USE_STATIC_LIBS ON)
find_package(OpenSSL REQUIRED COMPONENTS Crypto)
find_package(Protobuf CONFIG REQUIRED)

set(MUMBLE_CORE_PROTOBUF_LITE ON)
set(MUMBLE_CORE_PROTOC_ARGS PROTOC_EXE "${MV_DEPS_PREFIX}/host/bin/protoc")
set(3RDPARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mumble-core/3rdparty")
set(tests OFF)
add_subdirectory(mumble-core/src/mumble-core mumble-core EXCLUDE_FROM_ALL)

add_library(mvclient SHARED opus_jni.c media_jni.cpp)
target_link_libraries(mvclient PRIVATE opus mumble-core log)
target_compile_options(mvclient PRIVATE -Wall -Wextra -Werror)
target_link_options(mvclient PRIVATE
    # 16 KB pages (Android 15+ devices may use them); r28+ does this by default, but say it anyway.
    "-Wl,-z,max-page-size=16384"
    # Only the JNI entry points need to be visible; the static libraries' symbols stay inside.
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections"
)
