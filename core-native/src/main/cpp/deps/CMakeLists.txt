# Builds what mumble-core links against, for one Android ABI, as static libraries under
# CMAKE_INSTALL_PREFIX: OpenSSL libcrypto, and the Protobuf lite runtime with its Abseil. Also
# installs the host protoc of the same Protobuf release, since generated code only works against the
# runtime it was generated for.
#
# ../NativeDeps.cmake runs this once per ABI at configure time; it is not part of the app's build graph.
cmake_minimum_required(VERSION 3.22)
project(mv-native-deps C CXX)

include(ExternalProject)
include(${CMAKE_CURRENT_LIST_DIR}/versions.cmake)

set(PREFIX "${CMAKE_INSTALL_PREFIX}")
cmake_host_system_information(RESULT JOBS QUERY NUMBER_OF_LOGICAL_CORES)
set(TOOLCHAIN_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX=${PREFIX}
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_CXX_STANDARD=20
)

# The host protoc, prebuilt by the Protobuf project.
ExternalProject_Add(protoc
    URL ${PROTOC_URL}
    URL_HASH SHA256=${PROTOC_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CONFIGURE_COMMAND ""
    BUILD_COMMAND ""
    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <SOURCE_DIR>/bin/protoc ${PREFIX}/host/bin/protoc
)

# OpenSSL: libcrypto only. mumble-core needs AES-128-ECB, SHA-1/256 and RAND_bytes; TLS is the platform's.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPENSSL_TARGET android-arm64)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(OPENSSL_TARGET android-x86_64)
else()
    message(FATAL_ERROR "unsupported ABI ${ANDROID_ABI}")
endif()
string(REGEX REPLACE "^android-" "" API "${ANDROID_PLATFORM}")
set(OPENSSL_ENV ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK}
    PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin:$ENV{PATH})
ExternalProject_Add(openssl
    URL ${OPENSSL_URL}
    URL_HASH SHA256=${OPENSSL_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    BUILD_IN_SOURCE TRUE
    CONFIGURE_COMMAND ${OPENSSL_ENV} ./Configure ${OPENSSL_TARGET} -D__ANDROID_API__=${API}
        --prefix=${PREFIX} --libdir=lib
        no-shared no-module no-tests no-apps no-docs no-ssl no-engine no-dso no-ui-console
        # OpenSSL 3's default provider references every algorithm it has, so section GC cannot drop
        # them; switching them off is the only way. mumble-core needs AES, SHA-1/256 and the DRBG.
        no-aria no-bf no-blake2 no-camellia no-cast no-cms no-comp no-ct no-des no-dh no-dsa no-ec2m
        no-gost no-idea no-md4 no-mdc2 no-ocsp no-quic no-rc2 no-rc4 no-rmd160 no-scrypt no-seed
        no-siphash no-sm2 no-sm3 no-sm4 no-srp no-ts no-whirlpool no-ml-dsa no-ml-kem no-slh-dsa
        -ffunction-sections -fdata-sections
    BUILD_COMMAND ${OPENSSL_ENV} make -j${JOBS} build_libs
    INSTALL_COMMAND ${OPENSSL_ENV} make install_dev
)

ExternalProject_Add(abseil
    URL ${ABSEIL_URL}
    URL_HASH SHA256=${ABSEIL_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CMAKE_ARGS ${TOOLCHAIN_ARGS} -DABSL_PROPAGATE_CXX_STD=ON -DABSL_ENABLE_INSTALL=ON -DBUILD_TESTING=OFF
)

ExternalProject_Add(protobuf
    URL ${PROTOBUF_URL}
    URL_HASH SHA256=${PROTOBUF_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    DEPENDS abseil
    CMAKE_ARGS ${TOOLCHAIN_ARGS}
        -DCMAKE_FIND_ROOT_PATH=${PREFIX}
        -DCMAKE_PREFIX_PATH=${PREFIX}
        -Dprotobuf_LOCAL_DEPENDENCIES_ONLY=ON
        -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_CONFORMANCE=OFF -Dprotobuf_BUILD_EXAMPLES=OFF
        -Dprotobuf_BUILD_PROTOC_BINARIES=OFF -Dprotobuf_BUILD_LIBUPB=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF
        -Dprotobuf_INSTALL=ON -Dprotobuf_WITH_ZLIB=OFF
)
