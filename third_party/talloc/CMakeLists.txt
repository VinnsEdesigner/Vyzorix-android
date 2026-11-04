# talloc — vendored, built as a static library for Android.
#
# Why vendored: proot needs talloc (31 distinct talloc_* calls across its tree) and
# talloc is not in the NDK. Fetching a prebuilt would put a binary we did not compile
# into the trust path of the thing that fakes root, which is the same argument as the
# pinned server key.
#
# Why a hand-written CMake and not talloc's own build: talloc ships a waf build that
# probes the host with `./configure`. Cross-compiling that for Android means teaching
# waf about the NDK toolchain, which is more moving parts than the library needs —
# talloc is one C file. What it actually wants from its build is a `config.h` of
# HAVE_* answers, and CMake's check_* modules produce exactly that. So we generate it.
#
# Licence: talloc is LGPL-3.0-or-later (Samba). Vendored unmodified; see COPYING.

cmake_minimum_required(VERSION 3.22.1)

include(CheckIncludeFile)
include(CheckSymbolExists)
include(CheckTypeSize)
include(CheckCSourceCompiles)
include(CheckFunctionExists)

# talloc.c is compiled with these; the probes below answer the same questions the
# waf build would have.
set(CMAKE_REQUIRED_DEFINITIONS -D_GNU_SOURCE)

# --- Headers replace.h / talloc.c consult -------------------------------------
check_include_file("stdbool.h" HAVE_STDBOOL_H)
check_include_file("stdint.h" HAVE_STDINT_H)
check_include_file("inttypes.h" HAVE_INTTYPES_H)
check_include_file("limits.h" HAVE_LIMITS_H)
check_include_file("string.h" HAVE_STRING_H)
check_include_file("strings.h" HAVE_STRINGS_H)
check_include_file("unistd.h" HAVE_UNISTD_H)
check_include_file("sys/types.h" HAVE_SYS_TYPES_H)
check_include_file("sys/param.h" HAVE_SYS_PARAM_H)
check_include_file("sys/sysmacros.h" HAVE_SYS_SYSMACROS_H)
check_include_file("sys/auxv.h" HAVE_SYS_AUXV_H)
check_include_file("sys/atomic.h" HAVE_SYS_ATOMIC_H)
check_include_file("dlfcn.h" HAVE_DLFCN_H)
check_include_file("poll.h" HAVE_POLL)
check_include_file("utime.h" HAVE_UTIME)
check_include_file("sys/time.h" HAVE_UTIMES)
check_include_file("crypt.h" HAVE_CRYPT_H)
check_include_file("malloc.h" HAVE_MALLOC_H)
check_include_file("standards.h" HAVE_STANDARDS_H)
check_include_file("bsd/string.h" HAVE_BSD_STRING_H)
check_include_file("bsd/unistd.h" HAVE_BSD_UNISTD_H)
check_include_file("unix.h" HAVE_UNIX_H)

# valgrind headers are absent on Android, which is the answer we want: without them
# talloc compiles its no-op VALGRIND_* macros. Setting the cache entries explicitly
# avoids a probe that would fail to link for unrelated reasons.
set(HAVE_VALGRIND_H 0 CACHE INTERNAL "")
set(HAVE_VALGRIND_MEMCHECK_H 0 CACHE INTERNAL "")

# --- Types --------------------------------------------------------------------
set(CMAKE_EXTRA_INCLUDE_FILES stdint.h)
check_type_size("intptr_t" SIZEOF_INTPTR_T)
check_type_size("uintptr_t" SIZEOF_UINTPTR_T)
check_type_size("ptrdiff_t" SIZEOF_PTRDIFF_T)
unset(CMAKE_EXTRA_INCLUDE_FILES)
set(HAVE_INTPTR_T 1)
set(HAVE_UINTPTR_T 1)
set(HAVE_PTRDIFF_T 1)

# --- Functions replace.h offers to replace ------------------------------------
# replace.h turns `strdup` into `rep_strdup` (and so on) unless the matching
# HAVE_* is defined, so an unset entry is not "harmless" — it becomes a link error
# for a symbol nobody implements. Rather than hand-list them (and drift), the list
# is parsed out of replace.h: every HAVE_* it guards is probed.
#
# The probe is a *link* check, which is the right question: the header may declare
# the function and the symbol still be absent from the sysroot's libc.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/replace/replace.h" _replace_guarded
     REGEX "^#ifndef HAVE_[A-Z0-9_]+")
list(REMOVE_DUPLICATES _replace_guarded)

set(_replace_functions "")
foreach(_line IN LISTS _replace_guarded)
    string(REGEX MATCH "HAVE_[A-Z0-9_]+" _macro "${_line}")
    if(_macro)
        list(APPEND _replace_functions "${_macro}")
    endif()
endforeach()
list(SORT _replace_functions)
message(STATUS "talloc: probing ${_replace_functions}")

foreach(_macro IN LISTS _replace_functions)
    string(SUBSTRING "${_macro}" 5 -1 _func)
    string(TOLOWER "${_func}" _func_lower)
    # The header name for the function; most are in stdio/stdlib/string/unistd,
    # which replace.h has already included by this point in its own translation unit.
    check_function_exists("${_func_lower}" "${_macro}")
endforeach()

# The three that replace.h guards with `#if (defined(BROKEN_X) || !defined(HAVE_X))`
# rather than a plain #ifndef, so the parse above misses them. All exist on bionic.
check_function_exists("strnlen" HAVE_STRNLEN)
check_function_exists("vsnprintf" HAVE_VSNPRINTF)
check_function_exists("fprintf" HAVE_FPRINTF)
check_function_exists("printf" HAVE_PRINTF)
check_function_exists("memmem" HAVE_MEMMEM)
check_function_exists("memalign" HAVE_MEMALIGN)
check_function_exists("mkdtemp" HAVE_MKDTEMP)
check_function_exists("mktime" HAVE_MKTIME)
check_function_exists("timegm" HAVE_TIMEGM)
check_function_exists("utime" HAVE_UTIME)
check_function_exists("utimes" HAVE_UTIMES)
check_function_exists("strlcpy" HAVE_STRLCPY)
check_function_exists("strlcat" HAVE_STRLCAT)
check_function_exists("strcasestr" HAVE_STRCASESTR)
check_function_exists("strsep" HAVE_STRSEP)
check_function_exists("strtok_r" HAVE_STRTOK_R)
check_function_exists("strtoll" HAVE_STRTOLL)
check_function_exists("strtoull" HAVE_STRTOULL)
check_function_exists("ftruncate" HAVE_FTRUNCATE)
check_function_exists("initgroups" HAVE_INITGROUPS)
check_function_exists("seteuid" HAVE_SETEUID)
check_function_exists("setegid" HAVE_SETEGID)
check_function_exists("setlinebuf" HAVE_SETLINEBUF)
check_function_exists("setproctitle" HAVE_SETPROCTITLE)
check_function_exists("getprogname" HAVE_GETPROGNAME)
check_function_exists("copy_file_range" HAVE_COPY_FILE_RANGE)
check_function_exists("clock_gettime" HAVE_CLOCK_GETTIME)
check_function_exists("getpeereid" HAVE_GETPEEREID)
check_function_exists("strerror_r" HAVE_STRERROR_R)
check_function_exists("get_current_dir_name" HAVE_GET_CURRENT_DIR_NAME)
check_function_exists("pwrite" HAVE_PWRITE)
check_function_exists("pread" HAVE_PREAD)
check_function_exists("gethostbyname" HAVE_GETHOSTBYNAME)
check_function_exists("getifaddrs" HAVE_GETIFADDRS)
check_function_exists("freeifaddrs" HAVE_FREEIFADDRS)
check_function_exists("connect" HAVE_CONNECT)
check_function_exists("inet_ntoa" HAVE_INET_NTOA)
check_function_exists("inet_aton" HAVE_INET_ATON)
check_function_exists("inet_ntop" HAVE_INET_NTOP)
check_function_exists("inet_pton" HAVE_INET_PTON)
check_function_exists("chown" HAVE_CHOWN)
check_function_exists("chroot" HAVE_CHROOT)
check_function_exists("lchown" HAVE_LCHOWN)
check_function_exists("readlink" HAVE_READLINK)
check_function_exists("realpath" HAVE_REALPATH)
check_function_exists("symlink" HAVE_SYMLINK)
check_function_exists("link" HAVE_LINK)
check_function_exists("socketpair" HAVE_SOCKETPAIR)
check_function_exists("closefrom" HAVE_CLOSEFROM)
check_function_exists("dlopen" HAVE_DLOPEN)
check_function_exists("dlsym" HAVE_DLSYM)
check_function_exists("dlclose" HAVE_DLCLOSE)
check_function_exists("dlerror" HAVE_DLERROR)
check_function_exists("memset_s" HAVE_MEMSET_S)
check_function_exists("setproctitle_init" HAVE_SETPROCTITLE_INIT)

# --- Compiler capabilities talloc.c reads directly ----------------------------
# These three are the ones that change generated code, so they are worth a real
# probe rather than an assumption.
check_c_source_compiles(
    "int main(void) { __attribute__((constructor)) static void f(void) {} return 0; }"
    HAVE_CONSTRUCTOR_ATTRIBUTE)
check_c_source_compiles(
    "#pragma GCC diagnostic push\nint main(void) { return 0; }"
    HAVE_PRAGMA_INIT)
check_c_source_compiles(
    "#include <stdarg.h>\nint f(const char *fmt, ...) { va_list ap, cp; va_start(ap, fmt); __va_copy(cp, ap); va_end(ap); return 0; }\nint main(void){return 0;}"
    HAVE___VA_COPY)

# va_copy is the C99 spelling; glibc/bionic provide it. talloc prefers __va_copy.
set(HAVE_VA_COPY 1)

# HAVE_C99_VSNPRINTF gates the whole printf family in replace.h: fprintf, snprintf,
# vsnprintf, asprintf, vasprintf, dprintf and vdprintf are each mapped to rep_*
# unless it is set. Upstream decides it by *running* a conformance program
# (tests/snprintf.c), which is impossible when cross-compiling — and it is a
# behavioural question, so check_function_exists cannot answer it either: every one
# of those symbols exists, so each individual probe passes while this gate stays
# unset and the mappings stay on, producing undefined rep_* at link time.
#
# The answer for bionic is yes: Android's printf family is C99-conformant (%lld,
# positional arguments, %n$, correct return values). Asserted rather than probed,
# and if a future NDK changed that, the seven rep_* symbols would reappear at link
# and this comment is where to look.
set(HAVE_C99_VSNPRINTF 1)

# --- Generate config.h --------------------------------------------------------
# replace.h guards every use with #ifdef, so a missing entry is simply "not
# available". The entries written are derived from what was *probed*, not from a
# hand-kept list: a list drifts, and the failure mode is silent — a probe runs, its
# answer is never written, and replace.h maps the function to a rep_* symbol nobody
# implements. That is exactly how this first failed (rep_fprintf, rep_strnlen,
# rep_vsnprintf: probed, not written, so the mapping stayed on).
#
# Every HAVE_* variable that the probes above may set is collected here.
set(_talloc_config_entries "")

# The ones probed by name.
foreach(_macro IN LISTS _replace_functions)
    list(APPEND _talloc_config_entries "${_macro}")
endforeach()

list(APPEND _talloc_config_entries
    # probed explicitly above
    HAVE_ASPRINTF HAVE_DPRINTF HAVE_SNPRINTF HAVE_VASPRINTF HAVE_VDPRINTF
    HAVE_VSNPRINTF HAVE_STRNLEN HAVE_FPRINTF HAVE_PRINTF HAVE_MEMMEM
    HAVE_C99_VSNPRINTF HAVE_WORKING_STRPTIME
    HAVE_MEMALIGN HAVE_MKDTEMP HAVE_MKTIME HAVE_TIMEGM HAVE_UTIME HAVE_UTIMES
    HAVE_STRLCPY HAVE_STRLCPY HAVE_STRLCAT HAVE_STRCASESTR HAVE_STRSEP
    HAVE_STRTOK_R HAVE_STRTOLL HAVE_STRTOULL HAVE_FTRUNCATE HAVE_INITGROUPS
    HAVE_SETEUID HAVE_SETEGID HAVE_SETLINEBUF HAVE_SETPROCTITLE
    HAVE_GETPROGNAME HAVE_COPY_FILE_RANGE HAVE_CLOCK_GETTIME HAVE_GETPEEREID
    HAVE_STRERROR_R HAVE_GET_CURRENT_DIR_NAME HAVE_PWRITE HAVE_PREAD
    HAVE_GETHOSTBYNAME HAVE_GETIFADDRS HAVE_FREEIFADDRS HAVE_CONNECT
    HAVE_INET_NTOA HAVE_INET_ATON HAVE_INET_NTOP HAVE_INET_PTON HAVE_CHOWN
    HAVE_CHROOT HAVE_LCHOWN HAVE_READLINK HAVE_REALPATH HAVE_SYMLINK HAVE_LINK
    HAVE_SOCKETPAIR HAVE_CLOSEFROM HAVE_DLOPEN HAVE_DLSYM HAVE_DLCLOSE
    HAVE_DLERROR HAVE_MEMSET_S HAVE_SETPROCTITLE_INIT
    # headers
    HAVE_ASPRINTF HAVE_BZERO HAVE_CONSTRUCTOR_ATTRIBUTE HAVE_CRYPT_H
    HAVE_DLFCN_H HAVE_INTPTR_T HAVE_INTTYPES_H HAVE_LIMITS_H HAVE_MALLOC_H
    HAVE_MEMMOVE HAVE_MEMSET HAVE_POLL HAVE_PTRDIFF_T HAVE_PRAGMA_INIT
    HAVE_STDBOOL_H HAVE_STDINT_H HAVE_STRDUP HAVE_STRERROR HAVE_STRING_H
    HAVE_STRINGS_H HAVE_STRNDUP HAVE_SYS_ATOMIC_H HAVE_SYS_PARAM_H
    HAVE_SYS_SYSMACROS_H HAVE_SYS_TYPES_H HAVE_SYS_AUXV_H HAVE_UNISTD_H
    HAVE_UNIX_H HAVE_UNSETENV HAVE_USLEEP HAVE_VALGRIND_H
    HAVE_VALGRIND_MEMCHECK_H HAVE_VA_COPY HAVE___VA_COPY HAVE_BSD_STRING_H
    HAVE_BSD_UNISTD_H HAVE_STANDARDS_H HAVE_UINTPTR_T)
list(REMOVE_DUPLICATES _talloc_config_entries)
list(SORT _talloc_config_entries)

set(_talloc_config_body "")
foreach(_entry IN LISTS _talloc_config_entries)
    if(${_entry})
        string(APPEND _talloc_config_body "#define ${_entry} 1\n")
    else()
        string(APPEND _talloc_config_body "/* #undef ${_entry} */\n")
    endif()
endforeach()

foreach(_size SIZEOF_INTPTR_T SIZEOF_UINTPTR_T SIZEOF_PTRDIFF_T)
    if(${_size})
        string(APPEND _talloc_config_body "#define ${_size} ${${_size}}\n")
    endif()
endforeach()

# __STDC_WANT_LIB_EXT1__ must be 1 or replace.h #errors (it wants memset_s declared
# where available). Defining it here is what replace.h asks for.
#
# TALLOC_BUILD_VERSION_* is what talloc.c checks against TALLOC_VERSION_* in talloc.h,
# and it is normally written by talloc's waf `configure`. It is not optional: talloc.c
# #errors if the major/minor disagree, and the release number is folded into
# TALLOC_MAGIC_NON_RANDOM, which is the magic written into every allocation header —
# so a wrong value here is a runtime memory-corruption bug, not a build error. It is
# derived from talloc.h rather than hardcoded so a version bump is one edit.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/talloc.h" _talloc_version_lines
     REGEX "^#define TALLOC_VERSION_(MAJOR|MINOR) ")
foreach(_line IN LISTS _talloc_version_lines)
    if(_line MATCHES "TALLOC_VERSION_MAJOR +([0-9]+)")
        set(TALLOC_VERSION_MAJOR "${CMAKE_MATCH_1}")
    elseif(_line MATCHES "TALLOC_VERSION_MINOR +([0-9]+)")
        set(TALLOC_VERSION_MINOR "${CMAKE_MATCH_1}")
    endif()
endforeach()
if(NOT TALLOC_VERSION_MAJOR OR NOT TALLOC_VERSION_MINOR)
    message(FATAL_ERROR "talloc: could not read TALLOC_VERSION_{MAJOR,MINOR} from talloc.h")
endif()

# The release (patch) number is not in talloc.h; it is the third component of the
# upstream version. This tree is vendored from talloc 2.4.2.
set(TALLOC_BUILD_VERSION_RELEASE 2)

message(STATUS "talloc: version ${TALLOC_VERSION_MAJOR}.${TALLOC_VERSION_MINOR}.${TALLOC_BUILD_VERSION_RELEASE}")

set(TALLOC_CONFIG_H
"/* Generated by third_party/talloc/CMakeLists.txt — do not edit, and do not commit.
 *
 * talloc's own build probes the host with waf; this file is the same answers for
 * Android, produced by CMake's check_* modules. Regenerated on every configure.
 */
#ifndef TALLOC_CONFIG_H
#define TALLOC_CONFIG_H

#define _GNU_SOURCE 1
#define __STDC_WANT_LIB_EXT1__ 1
#define HAVE_COMPARISON_FN_T 1
#define HAVE_BOOL 1
#define HAVE_FUNCTION_MACRO 1
#define HAVE_VISIBILITY_ATTR 1
#define HAVE_VOLATILE 1
#define HAVE___THREAD 1
#define HAVE_DECL_EWOULDBLOCK 1
#define HAVE_ERRNO_DECL 1

/* talloc.c #errors if these disagree with talloc.h, and the release is folded into
 * TALLOC_MAGIC_NON_RANDOM — the magic in every allocation header. */
#define TALLOC_BUILD_VERSION_MAJOR ${TALLOC_VERSION_MAJOR}
#define TALLOC_BUILD_VERSION_MINOR ${TALLOC_VERSION_MINOR}
#define TALLOC_BUILD_VERSION_RELEASE ${TALLOC_BUILD_VERSION_RELEASE}

${_talloc_config_body}
#endif /* TALLOC_CONFIG_H */
")

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/include/config.h" "${TALLOC_CONFIG_H}")

# --- The library --------------------------------------------------------------
add_library(talloc STATIC talloc.c)

target_include_directories(talloc
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}"          # talloc.h
    PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/include"  # generated config.h
            "${CMAKE_CURRENT_SOURCE_DIR}/replace") # replace.h

# -DNO_CONFIG_H is deliberately NOT set: we generate one, and replace.h reads it.
# -fvisibility=hidden keeps talloc's internals out of the .so's dynamic table; the
# public API is marked in talloc.h.
target_compile_options(talloc PRIVATE
    -fvisibility=hidden
    -Wall -Wextra -Wno-unused-parameter)
