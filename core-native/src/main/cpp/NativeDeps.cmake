# Makes OpenSSL libcrypto, Protobuf lite (with Abseil) and the matching host protoc available for
# the ABI being configured, building them from the pinned sources in deps/ the first time.
#
# The result is cached outside the build tree, keyed by the pinned hashes, the API level and the NDK,
# so a clean app build does not rebuild them (they take a few minutes per ABI). MV_NATIVE_DEPS in the
# environment moves the cache; the default is ~/.cache/mumble-video-android/native-deps.
#
# Sets MV_DEPS_PREFIX.

include(${CMAKE_CURRENT_LIST_DIR}/deps/versions.cmake)

string(SHA256 _mv_deps_key
    "${NATIVE_DEPS_RECIPE};${OPENSSL_SHA256};${PROTOBUF_SHA256};${PROTOC_SHA256};${ABSEIL_SHA256};${ANDROID_PLATFORM};${ANDROID_NDK}")
string(SUBSTRING "${_mv_deps_key}" 0 16 _mv_deps_key)
if(DEFINED ENV{MV_NATIVE_DEPS})
    set(_mv_deps_root "$ENV{MV_NATIVE_DEPS}")
else()
    set(_mv_deps_root "$ENV{HOME}/.cache/mumble-video-android/native-deps")
endif()
set(MV_DEPS_PREFIX "${_mv_deps_root}/${_mv_deps_key}/${ANDROID_ABI}")

if(NOT EXISTS "${MV_DEPS_PREFIX}/.complete")
    # Gradle may configure several ABIs at once; each ABI builds into its own prefix.
    file(MAKE_DIRECTORY "${_mv_deps_root}/${_mv_deps_key}")
    file(LOCK "${_mv_deps_root}/${_mv_deps_key}/${ANDROID_ABI}.lock" TIMEOUT 3600)
    if(NOT EXISTS "${MV_DEPS_PREFIX}/.complete")
        message(STATUS "Building native dependencies for ${ANDROID_ABI} into ${MV_DEPS_PREFIX} (first time only)")
        set(_mv_deps_build "${MV_DEPS_PREFIX}.build")
        file(REMOVE_RECURSE "${MV_DEPS_PREFIX}" "${_mv_deps_build}")
        execute_process(
            COMMAND ${CMAKE_COMMAND} -S "${CMAKE_CURRENT_LIST_DIR}/deps" -B "${_mv_deps_build}"
                -G "${CMAKE_GENERATOR}" -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
                -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
                -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
                -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=${MV_DEPS_PREFIX}
            RESULT_VARIABLE _mv_result
        )
        if(NOT _mv_result EQUAL 0)
            message(FATAL_ERROR "configuring native dependencies failed")
        endif()
        execute_process(COMMAND ${CMAKE_COMMAND} --build "${_mv_deps_build}" RESULT_VARIABLE _mv_result)
        if(NOT _mv_result EQUAL 0)
            message(FATAL_ERROR "building native dependencies failed; the log is in ${_mv_deps_build}")
        endif()
        file(REMOVE_RECURSE "${_mv_deps_build}")
        file(TOUCH "${MV_DEPS_PREFIX}/.complete")
    endif()
    file(LOCK "${_mv_deps_root}/${_mv_deps_key}/${ANDROID_ABI}.lock" RELEASE)
endif()
