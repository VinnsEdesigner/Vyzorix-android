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
mapfile -t SOURCES < <(find "${ROOT}" \
  -path "${ROOT}/.git" -prune -o \
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

# clang-tidy needs a compile database. AGP writes one per ABI under
# android-core/.cxx after configureCMakeDebug; arm64 is used because the native
# code is ABI-independent.
DB="$(find "${ROOT}" -path "*/.cxx/*/arm64-v8a/compile_commands.json" -print -quit 2>/dev/null || true)"
if [[ -z "${DB}" ]]; then
  echo "native-gate: no compile database. Run:" >&2
  echo "  ./gradlew -PexportCompileCommands=true :android-core:configureCMakeDebug" >&2
  exit 1
fi

echo "native-gate: clang-tidy (db: ${DB#"${ROOT}/"})"
"${NDK_BIN}/clang-tidy" -p "$(dirname "${DB}")" --quiet \
  --warnings-as-errors='*' "${SOURCES[@]}"

echo "native-gate: OK"
