#!/usr/bin/env bash
#
# Build the Layer 3 container binaries for one Android ABI.
#
# Produces, in app-runtime/dist/:
#   layer3-<abi>.tar.gz   supervisor + app-* + android-bin, with the paths they
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
#   scripts/build-layer3.sh [--arch arm64|armeabi-v7a|x86_64] [--out DIR]
#
# The tarball's paths are relative to the overlay's /usr/local, so extracting it at
# files/overlay/usr-local installs:
#
#   bin/supervisor          the daemon
#   bin/app-*               the CLI
#   bin/android-bin         the bridge client, and its per-API symlinks
#
# That location is deliberate: /usr/local is a bind target of the writable overlay
# (§6.1), so these survive a rootfs replacement the same way app storage does.

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

if ! command -v "${CXX}" >/dev/null 2>&1; then
  echo "build-layer3: ${CXX} not found; run scripts/install-toolchain.sh" >&2
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
( cd "${ROOT}/app-runtime/supervisor" && make clean >/dev/null && make CXX="${CXX}" >/dev/null )
( cd "${ROOT}/app-runtime/cli-wrappers" && make clean >/dev/null && make CXX="${CXX}" >/dev/null )
( cd "${ROOT}/debian-engine/android-bin" && make clean >/dev/null && make CXX="${CXX}" >/dev/null )

cp "${ROOT}/app-runtime/supervisor/build/bin/supervisor" "${STAGE}/bin/"
cp "${ROOT}"/app-runtime/cli-wrappers/build/bin/app-* "${STAGE}/bin/"

# android-bin is a directory of symlinks beside the binary, so the whole directory
# is copied: the symlinks are relative to the binary and only resolve together.
cp "${ROOT}"/debian-engine/android-bin/build/bin/android-bin "${STAGE}/bin/"
for link in "${ROOT}"/debian-engine/android-bin/build/bin/*; do
  name="$(basename "${link}")"
  [[ "${name}" == "android-bin" ]] && continue
  ln -sf android-bin "${STAGE}/bin/${name}"
done

log "verifying the binaries are the right architecture"
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
  machine="$(readelf -h "${binary}" | awk -F: '/Machine:/ {print $2}' | xargs)"
  if [[ "${machine}" != "${expected_machine}" ]]; then
    echo "build-layer3: $(basename "${binary}") is ${machine}, expected ${expected_machine}" >&2
    exit 1
  fi
done
echo "all binaries are ${expected_machine}"

log "packing"
mkdir -p "${OUT}"
tar -C "${STAGE}" -czf "${OUT}/layer3-${ARCH}.tar.gz" .
ls -lh "${OUT}/layer3-${ARCH}.tar.gz"
tar -tzf "${OUT}/layer3-${ARCH}.tar.gz" | head -20

log "done"
