# proot (Termux fork) — vendored, cross-compiled to libproot.so for Android.
#
# Why the Termux fork and not upstream proot-me: upstream is written for a normal
# Linux where the app does not run under a seccomp filter. Android's app policy traps
# a large set of syscalls with SECCOMP_RET_TRAP, which cancels the syscall *before*
# proot's PTRACE_TRACEME can see it, and it also traps the "avoider" syscall number
# proot uses to cancel a syscall it emulates itself. Termux carries the fixes for
# exactly this environment (they run it under the same Android filter), so it is the
# supported base for an Android target. Upstream v5.5.0 needed a hand-written SIGSYS
# handler and still broke `chdir`, `poll` and `getaddrinfo` here.
#
# Licence: proot is GPL-2.0-or-later (see COPYING), compatible with this repo's
# GPL-3.0.
#
# Why a hand-written CMake: proot ships a hand-written GNUmakefile that assumes a
# host `gcc`, `pkg-config` and a `talloc.pc`, none of which exists for an NDK build,
# and the loader step is not expressible as a plain source list anyway (see below).

cmake_minimum_required(VERSION 3.22.1)

# The vendored revision, reported by `proot --version`. The GNUmakefile derives it
# from `git describe`; this tree is vendored (no git), so it is pinned here.
set(PROOT_VENDOR_VERSION "5.1.107.102-termux")

# The vendored tree is flat: src/'s contents live at third_party/proot/. So includes
# are "cli/cli.h", "syscall/syscall.h", etc., and the proot root is an include dir.

# --- Sources, from src/GNUmakefile's OBJECTS -----------------------------------
set(PROOT_SOURCES
    cli/cli.c
    cli/proot.c
    cli/note.c
    execve/enter.c
    execve/exit.c
    execve/shebang.c
    execve/elf.c
    execve/ldso.c
    execve/auxv.c
    execve/aoxp.c
    path/binding.c
    path/glue.c
    path/canon.c
    path/f2fs-bug.c
    path/path.c
    path/proc.c
    path/temp.c
    syscall/seccomp.c
    syscall/syscall.c
    syscall/chain.c
    syscall/enter.c
    syscall/exit.c
    syscall/sysnum.c
    syscall/socket.c
    syscall/heap.c
    syscall/rlimit.c
    syscall/pipe_shadow.c
    tracee/tracee.c
    tracee/mem.c
    tracee/reg.c
    tracee/event.c
    tracee/seccomp.c
    tracee/statx.c
    ptrace/ptrace.c
    ptrace/user.c
    ptrace/wait.c
    extension/extension.c
    extension/ashmem_memfd/ashmem_memfd.c
    extension/kompat/kompat.c
    extension/fake_id0/chown.c
    extension/fake_id0/chroot.c
    extension/fake_id0/getsockopt.c
    extension/fake_id0/sendmsg.c
    extension/fake_id0/socket.c
    extension/fake_id0/open.c
    extension/fake_id0/unlink.c
    extension/fake_id0/rename.c
    extension/fake_id0/chmod.c
    extension/fake_id0/utimensat.c
    extension/fake_id0/access.c
    extension/fake_id0/exec.c
    extension/fake_id0/link.c
    extension/fake_id0/symlink.c
    extension/fake_id0/mk.c
    extension/fake_id0/stat.c
    extension/fake_id0/helper_functions.c
    extension/fake_id0/fake_id0.c
    extension/hidden_files/hidden_files.c
    extension/mountinfo/mountinfo.c
    extension/port_switch/port_switch.c
    extension/sysvipc/sysvipc.c
    extension/sysvipc/sysvipc_msg.c
    extension/sysvipc/sysvipc_sem.c
    extension/sysvipc/sysvipc_shm.c
    extension/link2symlink/link2symlink.c
    extension/fix_symlink_size/fix_symlink_size.c)

# ==============================================================================
# build.h — proot's feature header.
#
# The GNUmakefile generates it by compiling and *running* two probe programs. Running
# is impossible when cross-compiling, and it is the wrong question: the probe answers
# "does this device support it", and proot may assume its own target. The same
# questions are asked at compile time with CMake instead.
#
#   * HAVE_PROCESS_VM     — process_vm_readv/writev exist; tracee/mem.c uses them as a
#                           fast path with a ptrace fallback when absent.
#   * HAVE_SECCOMP_FILTER — seccomp headers are usable. seccomp.c is *entirely*
#                           guarded by it, so a wrong answer changes behaviour, not
#                           just a warning.
#   * VERSION             — the vendored revision, reported by `proot --version`.
# ==============================================================================
include(CheckCSourceCompiles)

set(CMAKE_REQUIRED_DEFINITIONS -D_GNU_SOURCE)

check_c_source_compiles(
    "#include <sys/uio.h>
     #include <stdlib.h>
     int main(void) {
         return process_vm_readv(0, NULL, 0, NULL, 0, 0)
              + process_vm_writev(0, NULL, 0, NULL, 0, 0);
     }"
    HAVE_PROCESS_VM)

check_c_source_compiles(
    "#include <sys/prctl.h>
     #include <linux/seccomp.h>
     #include <linux/filter.h>
     #include <linux/audit.h>
     #include <stddef.h>
     int main(void) {
         struct sock_filter filter[] = {
             BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, arch)),
             BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE)
         };
         struct sock_fprog program;
         program.filter = filter;
         program.len = sizeof(filter) / sizeof(struct sock_filter);
         return program.len;
     }"
    HAVE_SECCOMP_FILTER)

unset(CMAKE_REQUIRED_DEFINITIONS)

if(HAVE_PROCESS_VM)
    set(_have_process_vm "#define HAVE_PROCESS_VM")
else()
    set(_have_process_vm "/* #undef HAVE_PROCESS_VM */")
endif()

if(HAVE_SECCOMP_FILTER)
    set(_have_seccomp "#define HAVE_SECCOMP_FILTER")
else()
    set(_have_seccomp "/* #undef HAVE_SECCOMP_FILTER */")
endif()

set(PROOT_BUILD_H
"/* Generated by third_party/proot/CMakeLists.txt — do not edit, and do not commit.
 *
 * proot's own build writes this by running probe programs on the host. That cannot
 * work when cross-compiling, so the same questions are answered at compile time here.
 */
#ifndef BUILD_H
#define BUILD_H
${_have_process_vm}
${_have_seccomp}
#undef VERSION
#define VERSION \"${PROOT_VENDOR_VERSION}\"
#endif /* BUILD_H */
")

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/build.h" "${PROOT_BUILD_H}")
message(STATUS "proot: HAVE_PROCESS_VM=${HAVE_PROCESS_VM} HAVE_SECCOMP_FILTER=${HAVE_SECCOMP_FILTER}")

# ==============================================================================
# Per-ABI loader parameters.
# ==============================================================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LOADER_BFD_ARCH "aarch64")
    set(LOADER_ELF_TARGET "elf64-littleaarch64")
    set(LOADER_ARCH_DEFS ARCH_ARM64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(LOADER_BFD_ARCH "arm")
    set(LOADER_ELF_TARGET "elf32-littlearm")
    set(LOADER_ARCH_DEFS ARCH_ARM_EABI)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(LOADER_BFD_ARCH "i386:x86-64")
    set(LOADER_ELF_TARGET "elf64-x86-64")
    set(LOADER_ARCH_DEFS ARCH_X86_64)
else()
    message(FATAL_ERROR "proot: unsupported ANDROID_ABI '${ANDROID_ABI}'")
endif()

# LOADER_ADDRESS is read from arch.h so the two cannot drift. arch.h's auto-detect
# guard lists ARCH_X86_64, ARCH_ARM_EABI, ARCH_X86 and ARCH_SH4 but NOT ARCH_ARM64,
# so defining ARCH_ARM64 by hand does not suppress host detection — the compiler's
# own `--target` is what makes __x86_64__ undefined. CMAKE_C_COMPILER_TARGET is set by
# the toolchain file, so reuse it rather than reconstructing a triple.
set(_cc_target_args "")
if(CMAKE_C_COMPILER_TARGET)
    list(APPEND _cc_target_args "--target=${CMAKE_C_COMPILER_TARGET}")
endif()

execute_process(
    COMMAND "${CMAKE_C_COMPILER}" ${_cc_target_args}
            -E -dM -DNO_LIBC_HEADER -D${LOADER_ARCH_DEFS}
            "${CMAKE_CURRENT_SOURCE_DIR}/arch.h"
    OUTPUT_VARIABLE _arch_defs
    RESULT_VARIABLE _arch_rc)
if(NOT _arch_rc EQUAL 0)
    message(FATAL_ERROR "proot: could not preprocess arch.h to read LOADER_ADDRESS")
endif()

string(REGEX MATCH "(^|\n)#define LOADER_ADDRESS (0x[0-9a-fA-F]+|[0-9]+)"
       _match "${_arch_defs}")
if(NOT CMAKE_MATCH_2)
    message(FATAL_ERROR "proot: LOADER_ADDRESS not found in arch.h for ${LOADER_ARCH_DEFS}")
endif()
set(LOADER_ADDRESS "${CMAKE_MATCH_2}")

string(REGEX MATCH "(^|\n)#define HAS_POKEDATA_WORKAROUND true" _pokedata "${_arch_defs}")
if(_pokedata)
    set(PROOT_HAS_POKEDATA TRUE)
else()
    set(PROOT_HAS_POKEDATA FALSE)
endif()
message(STATUS "proot: loader address ${LOADER_ADDRESS} (${ANDROID_ABI}), pokedata=${PROOT_HAS_POKEDATA}")

# ==============================================================================
# The loader.
#
# proot embeds a small freestanding program ("the loader") inside itself as a binary
# blob, then writes it to a temp file at runtime and points the traced process at it:
#
#   1. built `-fPIC -ffreestanding -nostdlib`, linked `-static -Ttext=<LOADER_ADDRESS>`;
#   2. copied to `loader.exe` and stripped;
#   3. `objcopy --input-target binary` into `loader-wrapped.o`, exporting
#      `_binary_loader_exe_start/_end` — the names execve/enter.c refers to.
#
# Step 3 is a custom command because CMake cannot express it as a source. The
# intermediate name must be `loader.exe` exactly: objcopy derives the exported symbol
# names from the input basename, and enter.c names `_binary_loader_exe_start`.
# ==============================================================================
set(_loader_unstripped "${CMAKE_CURRENT_BINARY_DIR}/loader.unstripped")
set(_loader_exe "${CMAKE_CURRENT_BINARY_DIR}/loader.exe")

# The pokedata workaround (arm64 only) needs the offset of the loader's
# `pokedata_workaround` symbol from its entry point, read out of the loader's symbol
# table. That read is done as an EXTRA COMMAND on the loader rule rather than as a
# separate rule that DEPENDS on the loader: two rules that share the loader as an
# output is a race, and ninja deleted `loader.unstripped` before the reader ran
# ("loader does not exist" on a file it had just built). One rule, ordered commands.
set(_loader_info_c "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c")
set(_loader_extra_outputs "")
set(_loader_extra_commands "")

if(PROOT_HAS_POKEDATA)
    get_filename_component(_toolchain_bin "${CMAKE_OBJCOPY}" DIRECTORY)
    set(_readelf "${_toolchain_bin}/llvm-readelf")
    if(NOT EXISTS "${_readelf}")
        message(FATAL_ERROR "proot: llvm-readelf not found at ${_readelf}")
    endif()

    list(APPEND _loader_extra_outputs "${_loader_info_c}")
    list(APPEND _loader_extra_commands
        COMMAND "${CMAKE_COMMAND}"
                -DREADELF="${_readelf}"
                -DLOADER="${_loader_unstripped}"
                -DOUT="${_loader_info_c}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/gen-loader-info.cmake")
endif()

# `loader.unstripped` is kept (not deleted) because the pokedata step reads its
# symbol table, which `strip` removes. The GNUmakefile reads `loader/loader`, the
# pre-strip artifact, for the same reason.
add_custom_command(
    OUTPUT "${_loader_exe}" "${_loader_unstripped}" ${_loader_extra_outputs}
    COMMAND "${CMAKE_C_COMPILER}" ${_cc_target_args}
            -fPIC -ffreestanding -static -nostdlib
            -D${LOADER_ARCH_DEFS} -DNO_LIBC_HEADER
            "-I${CMAKE_CURRENT_SOURCE_DIR}"
            -Wl,--build-id=none -Wl,-Ttext=${LOADER_ADDRESS} -Wl,-z,noexecstack
            "${CMAKE_CURRENT_SOURCE_DIR}/loader/loader.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly.S"
            -o "${_loader_unstripped}"
    ${_loader_extra_commands}
    COMMAND "${CMAKE_STRIP}" --strip-all "${_loader_unstripped}" -o "${_loader_exe}"
    DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/loader.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly.S"
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly-x86_64.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly-x86.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly-arm64.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/loader/assembly-arm.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/arch.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/compat.h"
    COMMENT "proot: building the ${ANDROID_ABI} loader"
    VERBATIM)

get_filename_component(_loader_dir "${_loader_exe}" DIRECTORY)
get_filename_component(_loader_name "${_loader_exe}" NAME)
set(_loader_obj "${CMAKE_CURRENT_BINARY_DIR}/loader-wrapped.o")
add_custom_command(
    OUTPUT "${_loader_obj}"
    COMMAND "${CMAKE_OBJCOPY}"
            --input-target binary
            --output-target "${LOADER_ELF_TARGET}"
            --binary-architecture "${LOADER_BFD_ARCH}"
            --rename-section .data=.rodata,alloc,load,readonly,data,contents
            "${_loader_name}" "${_loader_obj}"
    WORKING_DIRECTORY "${_loader_dir}"
    DEPENDS "${_loader_exe}"
    COMMENT "proot: embedding the ${ANDROID_ABI} loader"
    VERBATIM)

set(PROOT_LINK_EXTRA "${_loader_obj}")

# Compile the generated pokedata offset (arm64 only) into the link.
if(PROOT_HAS_POKEDATA)
    set(_loader_info_obj "${CMAKE_CURRENT_BINARY_DIR}/loader-info.o")
    add_custom_command(
        OUTPUT "${_loader_info_obj}"
        COMMAND "${CMAKE_C_COMPILER}" ${_cc_target_args}
                -D${LOADER_ARCH_DEFS}
                "-I${CMAKE_CURRENT_SOURCE_DIR}"
                -c "${_loader_info_c}" -o "${_loader_info_obj}"
        DEPENDS "${_loader_info_c}"
        COMMENT "proot: compiling the pokedata offset"
        VERBATIM)

    list(APPEND PROOT_LINK_EXTRA "${_loader_info_obj}")
endif()

# ==============================================================================
# The library itself.
#
# Built as a *PIE executable* named libproot.so. Two independent requirements fix the
# name and shape:
#   * ProotLauncher.prootBinary() looks for exactly `libproot.so` in nativeLibraryDir,
#     and AGP only packages `lib*.so` out of the native build dir — so the file must be
#     named that or it is silently absent from the APK;
#   * Android extracts a jniLibs .so with mode 0755, which is how a binary that is not
#     a library still gets to be executed (§1.1). A SHARED library has no entry point
#     and segfaults on exec; it must be an executable.
# ==============================================================================
add_executable(proot ${PROOT_SOURCES} ${PROOT_LINK_EXTRA})
set_target_properties(proot PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "proot"
    SUFFIX ".so"
    PIE TRUE)

target_include_directories(proot PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"     # build.h
    "${CMAKE_CURRENT_SOURCE_DIR}")    # arch.h, compat.h, tracee/, ...

target_link_libraries(proot PRIVATE talloc log)

target_compile_definitions(proot PRIVATE _GNU_SOURCE _FILE_OFFSET_BITS=64)
target_compile_options(proot PRIVATE
    -Wall -Wextra -O2
    -fno-strict-aliasing        # proot type-puns in the ptrace register unions
    -Wno-unused-parameter       # upstream is not warning-clean on that
    -Wno-sign-compare)

set_target_properties(proot PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON)
