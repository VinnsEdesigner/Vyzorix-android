#!/usr/bin/env bash
#
# Build `adb` for the container's userspace, as a publishable release asset.
#
#   scripts/build-adb.sh --arch arm64 [--out DIR]
#
# Produces `adb-<arch>.tar.gz`: the `adb` binary plus the shared libraries it needs,
# staged under `bin/` and `lib/` so extracting the tarball at the overlay's `/usr/local`
# puts `adb` on the container's PATH. It is **not** baked into the APK — it is published
# as its own release asset and installed only when an operator wants it.
#
# ## Why this is built, not installed
#
# There is no usable prebuilt: Debian's `android-tools-adb` is 5.1.1 (predates `adb pair`),
# and Termux's is built against bionic, which does not run in this glibc container. So adb
# is cross-built from `nmeum/android-tools` against a **Debian bookworm sysroot for the
# target ABI** — the same libc the container runs (glibc 2.36). A host build (glibc 2.41)
# would not load there.
#
# ## How the sysroot is assembled
#
# The build host may not be able to execute target-arch binaries (no binfmt in a locked-down
# sandbox), so the sysroot is assembled from Debian's own packages rather than built by
# running a compiler inside a chroot: download the `.deb` set, extract it, and point the
# toolchain at it with `CMAKE_SYSROOT`. This needs only a cross-compiler on the host.
#
# The output is dynamically linked, so the tarball carries the non-system `.so` set and the
# container-side helper sets `LD_LIBRARY_PATH`.
#
# Env:
#   VYZORIX_ADB_VERSION   android-tools tag (default 37.0.0p1)

set -euo pipefail

ARCH="arm64"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/debian-container/adb/dist"
SUITE="bookworm"
VERSION="${VYZORIX_ADB_VERSION:-37.0.0p1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    --out)  OUT="$2";  shift 2 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

# The Android ABI name -> Debian arch + cross toolchain prefix + the sysroot's multiarch dir.
case "${ARCH}" in
  arm64)       DEB_ARCH="arm64" ; GNU="aarch64-linux-gnu"   ; MULTI="aarch64-linux-gnu" ;;
  armeabi-v7a) DEB_ARCH="armhf" ; GNU="arm-linux-gnueabihf" ; MULTI="arm-linux-gnueabihf" ;;
  x86_64)      DEB_ARCH="amd64" ; GNU=""                    ; MULTI="x86_64-linux-gnu" ;;
  *) echo "unsupported arch: ${ARCH} (use arm64, armeabi-v7a or x86_64)" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}" 2>/dev/null || true' EXIT

log() { printf '\n=== %s\n' "$*"; }
die() { printf '\n!!! %s\n' "$*" >&2; exit 1; }

[[ -f "${VYZORIX_TC:-/workspace/toolchain}/env.sh" ]] && \
  source "${VYZORIX_TC:-/workspace/toolchain}/env.sh"

if [[ -n "${GNU}" ]]; then
  command -v "${GNU}-g++" >/dev/null 2>&1 || \
    die "${GNU}-g++ is required (apt-get install g++-${GNU})"
fi

mkdir -p "${OUT}"
SYSROOT="${WORK}/sysroot"

# --------------------------------------------------------------------------- sysroot
# The packages android-tools needs, resolved from the bookworm index so the versions are
# pinned (a moving `stable` would drift the sysroot's glibc). Resolved by path, not name,
# so no apt sources for a foreign arch are required.
log "assembling a ${SUITE} ${DEB_ARCH} sysroot from Debian packages"
PKGS="
libc6-dev libstdc++-12-dev libgcc-12-dev linux-libc-dev
libprotobuf-dev libprotobuf32 libabsl-dev libabsl20220623
libbrotli-dev libbrotli1 liblz4-dev liblz4-1 libzstd-dev libzstd1
libpcre2-dev libpcre2-8-0 libgtest-dev libusb-1.0-0-dev libusb-1.0-0
zlib1g-dev zlib1g
"
INDEX="${WORK}/Packages"
curl -fsSL "http://deb.debian.org/debian/dists/${SUITE}/main/binary-${DEB_ARCH}/Packages.gz" \
  | gunzip > "${INDEX}"

mkdir -p "${SYSROOT}"
for pkg in ${PKGS}; do
  # The first matching stanza's Filename: — the index lists one version per suite.
  path="$(awk -v p="${pkg}" '
    $1=="Package:" && $2==p {want=1; next}
    want && $1=="Filename:" {print $2; exit}
  ' "${INDEX}")"
  [[ -n "${path}" ]] || die "no ${pkg} in the ${SUITE} ${DEB_ARCH} index"
  deb="${WORK}/$(basename "${path}")"
  curl -fsSL -o "${deb}" "http://deb.debian.org/debian/${path}"
  dpkg-deb -x "${deb}" "${SYSROOT}"
done

# --------------------------------------------------------------------------- build
log "fetching android-tools ${VERSION}"
curl -fsSL -o "${WORK}/at.tar.xz" \
  "https://github.com/nmeum/android-tools/releases/download/${VERSION}/android-tools-${VERSION}.tar.xz"
tar -C "${WORK}" -xf "${WORK}/at.tar.xz"
SRC="${WORK}/android-tools-${VERSION}"

# The toolchain file pins the compiler and the sysroot, and keeps CMake's find_* from
# reaching back to the host's libraries (which would link a host glibc into the result).
TOOLCHAIN="${WORK}/toolchain.cmake"
cat > "${TOOLCHAIN}" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR ${DEB_ARCH})
set(CMAKE_SYSROOT ${SYSROOT})
set(CMAKE_C_COMPILER ${GNU:+${GNU}-}gcc)
set(CMAKE_CXX_COMPILER ${GNU:+${GNU}-}g++)
set(CMAKE_FIND_ROOT_PATH ${SYSROOT})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
set(ENV{PKG_CONFIG_SYSROOT_DIR} ${SYSROOT})
set(ENV{PKG_CONFIG_LIBDIR} ${SYSROOT}/usr/lib/${MULTI}/pkgconfig:${SYSROOT}/usr/share/pkgconfig)
EOF

log "cross-building adb for ${ARCH}"
cmake -S "${SRC}" -B "${SRC}/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_TOOLS_ADB_ENABLE_MDNS=OFF \
  -DANDROID_TOOLS_LIBUSB_ENABLE_UDEV=OFF \
  -DANDROID_TOOLS_USE_BUNDLED_FMT=ON \
  -DANDROID_TOOLS_USE_BUNDLED_LIBUSB=ON
ninja -C "${SRC}/build" adb

ADB_BIN="${SRC}/build/vendor/adb"
[[ -f "${ADB_BIN}" ]] || die "the build produced no adb at ${ADB_BIN}"

# --------------------------------------------------------------------------- stage
log "staging the tarball"
STAGE="${WORK}/stage"
mkdir -p "${STAGE}/bin" "${STAGE}/lib"

cp "${ADB_BIN}" "${STAGE}/bin/adb"
chmod 0755 "${STAGE}/bin/adb"

# The container-side helper, shipped beside adb so the pairing flow is one command.
install -m 0755 "${ROOT}/debian-container/adb/adbwireless" "${STAGE}/bin/adbwireless"

# adb's non-system shared libraries. The libc, libstdc++ and libgcc come from the
# container's rootfs, so they are NOT shipped; the rest (protobuf, brotli, lz4, zstd, z)
# are not in the base rootfs and are.
log "collecting adb's shared libraries"
mapfile -t NEEDED < <(
  "${GNU:+${GNU}-}readelf" -d "${ADB_BIN}" 2>/dev/null \
    | awk '/NEEDED/ {for (i=1;i<=NF;i++) if ($i ~ /^\[/) {gsub(/[\[\]]/,"",$i); print $i}}' \
    | sort -u
)
for lib in "${NEEDED[@]}"; do
  case "${lib}" in
    libc.so.6|libm.so.6|libpthread.so.0|libdl.so.2|librt.so.1|libstdc++.so.6|libgcc_s.so.1|ld-linux*)
      continue ;;  # provided by the container's rootfs
  esac
  # Follow the .so symlink to the real file in the sysroot.
  real="$(find "${SYSROOT}" -name "${lib}" 2>/dev/null | head -1)"
  [[ -n "${real}" ]] || die "sysroot has no ${lib}"
  cp -L "${real}" "${STAGE}/lib/${lib}"
done

OUT_TGZ="${OUT}/adb-${ARCH}.tar.gz"
tar -C "${STAGE}" -czf "${OUT_TGZ}" bin lib
( cd "${OUT}" && sha256sum "adb-${ARCH}.tar.gz" > "adb-${ARCH}.tar.gz.sha256" )

log "built ${OUT_TGZ}"
du -h "${OUT_TGZ}" | cut -f1
echo "  adb:    $("${GNU:+${GNU}-}readelf" -h "${STAGE}/bin/adb" | awk -F: '/Machine:/ {print $2}' | xargs)"
echo "  libs:   $(ls "${STAGE}/lib" | tr '\n' ' ')"
echo
echo "Publish it with the release tooling, e.g.:"
echo "  gh release upload <tag> ${OUT_TGZ} ${OUT_TGZ}.sha256"
