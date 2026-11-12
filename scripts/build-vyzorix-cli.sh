#!/usr/bin/env bash
#
# Build the the Vyzorix CLI container binaries for one Android ABI.
#
# Produces, in app-runtime/dist/:
#   vyzorix-cli-<abi>.tar.gz   supervisor + app-* + android-bin, with the paths they
#                         install to inside the container
#
# These binaries run **inside the Debian userspace**, whose libc is glibc — not
# Android's bionic — so they are cross-compiled with a GNU toolchain, never the NDK.
# The mapping is the same one build-rootfs.sh uses:
#
#   arm64       -> aarch64-linux-gnu-g++
#   armeabi-v7a -> arm-linux-gnueabihf-g++
#   x86_64      -> g++ (native)
#
# scripts/install-toolchain.sh installs both cross-compilers.
#
# Usage:
#   scripts/build-vyzorix-cli.sh [--arch arm64|armeabi-v7a|x86_64] [--out DIR]
#
# The tarball's paths are relative to the overlay's /usr/local, so extracting it at
# files/overlay/usr-local installs:
#
#   bin/supervisor          the daemon
#   bin/app-*               the CLI
#   bin/android-bin         the bridge client, and its per-API symlinks
#
# That location is deliberate: /usr/local is a bind target of the writable overlay
# so these survive a rootfs replacement the same way app storage does.

set -euo pipefail

ARCH="x86_64"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app-runtime/dist"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    --out)  OUT="$2";  shift 2 ;;
    --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "${ARCH}" in
  arm64)       CXX="aarch64-linux-gnu-g++" ;;
  armeabi-v7a) CXX="arm-linux-gnueabihf-g++" ;;
  x86_64)      CXX="g++" ;;
  *) echo "unsupported arch: ${ARCH} (use arm64, armeabi-v7a or x86_64)" >&2; exit 2 ;;
esac

# **Static linking is required, not a preference.**
#
# These binaries run inside the Debian rootfs, which is Debian 12 (glibc 2.36), while
# the toolchain that builds them is the host's (Debian 13, glibc 2.41). A dynamically
# linked binary therefore fails on the device with
#
#   /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_2.38' not found
#
# and the supervisor never starts — a failure that only appears on a device, because
# on the host the same binary runs perfectly. Static linking removes the dependency
# entirely, which also means these tools keep working if the rootfs is ever rebuilt
# on a different Debian release.
#
# The tools are safe to link statically: no dlopen, no NSS, no locale beyond C, no
# getaddrinfo. They use files, sockets, fork/exec and the C++ standard library.
LDFLAGS_STATIC="-static -static-libgcc -static-libstdc++"
export LDFLAGS_STATIC

if ! command -v "${CXX}" >/dev/null 2>&1; then
  echo "build-vyzorix-cli: ${CXX} not found; run scripts/install-toolchain.sh" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

log() { printf '\n=== %s\n' "$*"; }

STAGE="${WORK}/stage"
mkdir -p "${STAGE}/bin"

log "building for ${ARCH} with ${CXX}"

# Each tool builds into its own build/bin; they are copied together here so the
# tarball has the container layout rather than the source layout.
( cd "${ROOT}/app-runtime/supervisor" && make clean >/dev/null && make CXX="${CXX}" LDFLAGS="${LDFLAGS_STATIC}" >/dev/null )
( cd "${ROOT}/app-runtime/cli-wrappers" && make clean >/dev/null && make CXX="${CXX}" LDFLAGS="${LDFLAGS_STATIC}" >/dev/null )
( cd "${ROOT}/debian-container/android-bin" && make clean >/dev/null && make CXX="${CXX}" LDFLAGS="${LDFLAGS_STATIC}" >/dev/null )

cp "${ROOT}/app-runtime/supervisor/build/bin/supervisor" "${STAGE}/bin/"
cp "${ROOT}"/app-runtime/cli-wrappers/build/bin/app-* "${STAGE}/bin/"
# The front door: `vyzorix` lists and dispatches the commands below it, and
# VERSION is what `vyzorix version` prints.
cp "${ROOT}/app-runtime/cli-wrappers/build/bin/vyzorix" "${STAGE}/bin/vyzorix"
cp "${ROOT}/app-runtime/cli-wrappers/build/bin/VERSION" "${STAGE}/bin/VERSION"
chmod 0755 "${STAGE}/bin/vyzorix"

# android-bin is a directory of symlinks beside the binary, so the whole directory
# is copied: the symlinks are relative to the binary and only resolve together.
cp "${ROOT}"/debian-container/android-bin/build/bin/android-bin "${STAGE}/bin/"
for link in "${ROOT}"/debian-container/android-bin/build/bin/*; do
  name="$(basename "${link}")"
  [[ "${name}" == "android-bin" ]] && continue
  ln -sf android-bin "${STAGE}/bin/${name}"
done

log "verifying the binaries are static"
# A wrong-arch binary is the failure that reads as "corrupt rootfs", so it is
# checked here rather than discovered on a device.
expected_machine="$(
  case "${ARCH}" in
    arm64) echo "AArch64" ;;
    armeabi-v7a) echo "ARM" ;;
    x86_64) echo "Advanced Micro Devices X86-64" ;;
  esac
)"
for binary in "${STAGE}"/bin/*; do
  [[ -f "${binary}" && ! -L "${binary}" ]] || continue

  # Only ELF files are checked: `vyzorix` is a shell script and `VERSION` is text.
  # `readelf` fails on those, and neither is a compiled binary to verify.
  head -c 4 "${binary}" 2>/dev/null | grep -q $'\x7fELF' || continue

  machine="$(readelf -h "${binary}" | awk -F: '/Machine:/ {print $2}' | xargs)"
  if [[ "${machine}" != "${expected_machine}" ]]; then
    echo "build-vyzorix-cli: $(basename "${binary}") is ${machine}, expected ${expected_machine}" >&2
    exit 1
  fi

  # A dynamic binary here is the bug that only shows on a device, so it is a build
  # failure rather than a warning.
  if readelf -d "${binary}" 2>/dev/null | grep -q "NEEDED"; then
    echo "build-vyzorix-cli: $(basename "${binary}") is dynamically linked; it will not" >&2
    echo "  load against the rootfs glibc. The static flags are not reaching the link." >&2
    exit 1
  fi
done
echo "all binaries are ${expected_machine} and statically linked"

log "packing"
mkdir -p "${OUT}"
tar -C "${STAGE}" -czf "${OUT}/vyzorix-cli-${ARCH}.tar.gz" .
ls -lh "${OUT}/vyzorix-cli-${ARCH}.tar.gz"
# The listing is a convenience, and `tar | head` is a SIGPIPE race: head exits after
# 20 lines, tar gets EPIPE, and with `set -o pipefail` the whole build fails
# intermittently. The archive is already verified above, so a failure to *list* it
# must not fail the build — hence the explicit tolerance rather than a bare pipe.
tar -tzf "${OUT}/vyzorix-cli-${ARCH}.tar.gz" 2>/dev/null | head -20 || true

log "done"
