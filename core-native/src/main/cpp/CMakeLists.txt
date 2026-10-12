cmake_minimum_required(VERSION 3.22)
project(mumblevideo_client C CXX)

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

add_library(mvclient SHARED opus_jni.c)
target_link_libraries(mvclient PRIVATE opus log)
target_compile_options(mvclient PRIVATE -Wall -Wextra -Werror)
# 16 KB pages (Android 15+ devices may use them); r28+ does this by default, but say it anyway.
target_link_options(mvclient PRIVATE "-Wl,-z,max-page-size=16384")
