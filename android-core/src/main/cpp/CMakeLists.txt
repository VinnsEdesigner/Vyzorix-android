cmake_minimum_required(VERSION 3.22.1)
# C is enabled alongside C++ because the vendored native dependencies are C: talloc
# and proot are C, and their configure-time probes use check_c_source_compiles, which
# requires the C language to be enabled by the top-level project().
project(vyzorix_core C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored native dependencies.
#
# Both are built from source in this tree rather than fetched, because both end up
# in the trust path of the thing that fakes root inside the container:
#
#   * talloc — proot's allocator. Not in the NDK. Its own build is waf-based and
#     probes the host; the vendored CMake answers the same questions with check_*.
#   * proot — the userspace chroot. Its GNUmakefile assumes host gcc/pkg-config, and
#     its embedded loader needs an objcopy step CMake cannot express as a source.
#
# They are subdirectories, not separate projects, so one configure covers all three
# and the NDK toolchain is applied once.
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/talloc"
    "${CMAKE_CURRENT_BINARY_DIR}/talloc"
    EXCLUDE_FROM_ALL)
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/proot"
    "${CMAKE_CURRENT_BINARY_DIR}/proot"
    EXCLUDE_FROM_ALL)

add_library(vyzorix_core SHARED
    pty_native.cpp
    pty_exec.cpp
    fd_pass_native.cpp
    jni_bridge.cpp
    self_test.cpp
)

target_include_directories(vyzorix_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(vyzorix_core ${log-lib})

# libproot.so is a *separate* .so that Android packages as a native library, so it is
# built but not linked into vyzorix_core. The dependency is declared so a build of
# this module produces it — otherwise `:android-core:assembleDebug` would leave the
# engine binary absent, and ProotLauncher.isReady() would silently report "not
# installed".
add_dependencies(vyzorix_core proot)
