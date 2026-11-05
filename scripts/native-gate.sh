#!/usr/bin/env bash
#
# Native (C++) gate: clang-format and clang-tidy.
#
# Both tools ship with the NDK, so the gate uses those rather than requiring a
# separate install. The versions then match the toolchain that compiles the code,
# which matters: a formatter from a different LLVM release will disagree.
#
# Usage:
#   scripts/native-gate.sh          check (fails on any finding)
#   scripts/native-gate.sh --fix    format in place, then check
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="26.3.11579264"
NDK_BIN="${ANDROID_HOME:-/opt/android-sdk}/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin"

FIX=0
if [[ "${1:-}" == "--fix" ]]; then
  FIX=1
fi

if [[ ! -x "${NDK_BIN}/clang-format" ]]; then
  echo "native-gate: clang-format not found at ${NDK_BIN}" >&2
  echo "native-gate: set ANDROID_HOME, or install NDK ${NDK_VERSION}" >&2
  exit 1
fi

# Every native source tree, discovered rather than listed, so a new module is
# covered without editing this script.
#
# `third_party/` is excluded on purpose: it is vendored upstream code (proot, talloc)
# kept byte-for-byte so an upgrade is a clean diff. Reformatting it would make every
# future upstream merge a conflict and would be reformatting someone else's code to
# our style for no benefit. The gate still covers everything we wrote.
mapfile -t SOURCES < <(find "${ROOT}" \
  -path "${ROOT}/.git" -prune -o \
  -path "${ROOT}/third_party" -prune -o \
  -path "*/build" -prune -o \
  -path "*/.cxx" -prune -o \
  \( -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.cc' \) -print)

if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "native-gate: no native sources found"
  exit 0
fi

echo "native-gate: ${#SOURCES[@]} file(s)"

if [[ ${FIX} -eq 1 ]]; then
  "${NDK_BIN}/clang-format" -i "${SOURCES[@]}"
  echo "native-gate: formatted"
fi

echo "native-gate: clang-format"
"${NDK_BIN}/clang-format" --dry-run --Werror "${SOURCES[@]}"

# clang-tidy runs only on the sources the NDK actually compiles.
#
# Its compile database is AGP's (`android-core/.cxx/*/compile_commands.json`), and
# tidy needs a compile command for every file it is given: without one it cannot
# resolve the include paths or the language standard, so it reports
# "'vyzorix/json.h' file not found" — a diagnostic error that hides the real ones.
#
# The container tools (`debian-engine/android-bin`, `app-runtime/*`, `native/lib`)
# are not in that database: they are built by their own Makefiles with a GNU cross
# toolchain, because they run in the Debian userspace (glibc) rather than on Android
# (bionic). Their correctness is covered by their own `make check` suites and the
# on-device tests.
#
# So the tidy set is derived from the database rather than from a hand-kept list of
# exclusions: a file is tidy'd exactly when the database can describe how to build
# it. Adding a module to the NDK build therefore brings it under tidy with no change
# here, which is the property a hand-list loses.
mapfile -t DB_FILES < <(python3 -c "
import json, sys
with open(sys.argv[1]) as f:
    for entry in json.load(f):
        print(entry['file'])
" "${DB}" 2>/dev/null | sort -u)

TIDY_SOURCES=()
for source in "${SOURCES[@]}"; do
    if printf '%s\n' "${DB_FILES[@]}" | grep -qxF "${source}"; then
        TIDY_SOURCES+=("${source}")
    fi
done

if [[ ${#TIDY_SOURCES[@]} -eq 0 ]]; then
  echo "native-gate: no source is in the compile database; skipping clang-tidy"
  exit 0
fi

echo "native-gate: clang-tidy (db: ${DB#"${ROOT}/"}, ${#TIDY_SOURCES[@]} file(s))"
"${NDK_BIN}/clang-tidy" -p "$(dirname "${DB}")" --quiet \
  --warnings-as-errors='*' "${TIDY_SOURCES[@]}"

echo "native-gate: OK"
