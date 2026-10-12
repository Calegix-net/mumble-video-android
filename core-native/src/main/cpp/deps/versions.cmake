# Pinned native dependencies. Bump a Protobuf release as a set (runtime source, host protoc, and the
# Abseil named in its cmake/dependencies.cmake), and change every hash with its version.

set(OPENSSL_VERSION 3.5.9) # LTS
set(OPENSSL_URL https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz)
set(OPENSSL_SHA256 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a)

set(PROTOBUF_VERSION 33.5) # C++ runtime 6.33.5
set(PROTOBUF_URL https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOBUF_VERSION}/protobuf-${PROTOBUF_VERSION}.tar.gz)
set(PROTOBUF_SHA256 c6c7c27fadc19d40ab2eaa23ff35debfe01f6494a8345559b9bb285ce4144dd1)
set(PROTOC_URL https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOBUF_VERSION}/protoc-${PROTOBUF_VERSION}-linux-x86_64.zip)
set(PROTOC_SHA256 24e58fb231d50306ee28491f33a170301e99540f7e29ca461e0e80fd1239f8d1)

set(ABSEIL_VERSION 20250512.1)
set(ABSEIL_URL https://github.com/abseil/abseil-cpp/archive/refs/tags/${ABSEIL_VERSION}.tar.gz)
set(ABSEIL_SHA256 9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db)

# Bump when deps/CMakeLists.txt changes how anything is built, so cached builds are not reused.
set(NATIVE_DEPS_RECIPE 3)
