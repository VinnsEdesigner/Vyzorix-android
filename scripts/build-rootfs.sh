#!/usr/bin/env bash
#
# Build the Debian rootfs tarballs for the container engine.
#
# Produces, in the output directory:
#   debian-core-<abi>.tar.gz      the floor: shell, userland, TLS trust, socat
#   debian-extended-<abi>.tar.gz  git, python3, tmux, the usual tooling
#   SHA256SUMS                    checksums, which the device verifies before extracting
#
# The ABI is in the filename because there is one tarball per architecture and three
# architectures ship: a name without it would overwrite itself on the second build.
#
# See doc/ANDROID_SERVICE_ARCHITECTURE.md §6.1.1 for what goes in each and why.
#
# Usage:
#   scripts/build-rootfs.sh [--arch arm64|armeabi-v7a|x86_64] [--out DIR]
#
# The default is arm64 (the large majority of Android devices). The app targets the
# general Android public, so the ABIs it ships each need a matching rootfs:
#   arm64   -> arm64   (most current devices)
#   armeabi-v7a -> armhf (older 32-bit devices)
#   x86_64  -> amd64   (emulators, and the rare x86 tablet)
# A rootfs built for one architecture cannot run on another; the failure is a
# `bin/bash` that exists and cannot execute.
#
# Building a foreign arch needs qemu-user-static *and* a registered binfmt_misc
# handler; see debian-engine/rootfs/README.md for why, and for the check that proves
# it before a long build.
#
# Reproducibility: the suite is pinned to bookworm (Debian 12), not `stable`, and
# every package version is resolved at build time and recorded in BUILD.md. Two runs
# on the same day produce the same set; two runs months apart will not, because
# Debian moves. That is the trade for not vendoring a 250 MB tarball in git.

set -euo pipefail

ARCH="arm64"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/debian-engine/rootfs/dist"
SUITE="bookworm"
MIRROR="http://deb.debian.org/debian"
SECURITY_MIRROR="http://deb.debian.org/debian-security"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    --out)  OUT="$2";  shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# The Android ABI name -> Debian architecture name. They are not the same vocabulary:
# Android calls a 32-bit ARM device `armeabi-v7a`, Debian calls it `armhf`.
case "${ARCH}" in
  arm64)       DEB_ARCH="arm64" ;;
  armeabi-v7a) DEB_ARCH="armhf" ;;
  x86_64)      DEB_ARCH="amd64" ;;
  amd64)       DEB_ARCH="amd64" ;;  # accepted as an alias; the ABI name is x86_64
  *) echo "unsupported arch: ${ARCH} (use arm64, armeabi-v7a or x86_64)" >&2; exit 2 ;;
esac

# The package sets. Kept here rather than inline so the two tarballs read as a pair
# and a reviewer can see exactly what is in each.
#
# Rationale for every entry is in §6.1.1. The short version: `ca-certificates` is
# not optional (no TLS trust means no HTTPS), `socat` is the container-side bridge
# transport, and `tmux` is what makes a dropped WebSocket reattach rather than lose
# the session. No init system (proot execs bash directly, there is no PID 1), no
# sudo (`-0` fakes root), no compiler.
CORE_PACKAGES=(
  bash coreutils util-linux procps sed grep gawk findutils tar gzip xz-utils
  ca-certificates curl socat less nano
)

EXTENDED_PACKAGES=(
  git python3 openssh-client tmux iproute2 iputils-ping dnsutils strace htop
)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

mkdir -p "${OUT}"

log() { printf '\n=== %s\n' "$*"; }

# ---------------------------------------------------------------------------
# One tarball, from a fresh minbase plus the given packages.
#
# The extra packages are installed in a second debootstrap pass rather than with
# `apt-get install` inside a chroot: debootstrap's --include is what makes the
# result a single coherent set, and it avoids needing to configure apt sources in
# the image (which would then have to be cleaned out again).
# ---------------------------------------------------------------------------
build_tarball() {
  local name="$1"; shift
  local -a packages=("$@")
  local dir="${WORK}/${name}"

  log "debootstrap ${name} (${DEB_ARCH}, ${SUITE}, ${#packages[@]} extra packages)"
  # --variant=minbase gives a minimal but apt-capable base. --components=main only:
  # contrib/non-free would pull in firmware we neither need nor can redistribute.
  sudo debootstrap \
    --arch="${DEB_ARCH}" \
    --variant=minbase \
    --components=main \
    --include="$(IFS=,; echo "${packages[*]}")" \
    --keyring=/usr/share/keyrings/debian-archive-keyring.gpg \
    "${SUITE}" "${dir}" "${MIRROR}"

  log "pruning ${name}"
  # Everything below is dead weight in a container image and, in the apt cases,
  # actively misleading (a stale package list that no longer matches the archive).
  sudo rm -rf \
    "${dir}/var/cache/apt"/* \
    "${dir}/var/lib/apt/lists"/* \
    "${dir}/var/lib/apt/lists"/partial \
    "${dir}/var/log"/* \
    "${dir}/tmp"/* \
    "${dir}/usr/share/doc"/* \
    "${dir}/usr/share/man"/* \
    "${dir}/usr/share/info"/* \
    "${dir}/usr/share/lintian"/*

  # /opt and /usr/local must exist but be EMPTY: the writable overlay binds over
  # them (§6.1). Shipping content there would be shadowed and look like a bug.
  sudo mkdir -p "${dir}/opt" "${dir}/usr/local"
  sudo find "${dir}/opt" "${dir}/usr/local" -mindepth 1 -delete 2>/dev/null || true

  # resolv.conf must exist as a *bind target* for proot's -b (§6.2). Its contents are
  # replaced by the bind, so an empty file is enough — but it must be present.
  sudo mkdir -p "${dir}/etc"
  sudo touch "${dir}/etc/resolv.conf"

  # Nothing setuid: meaningless under proot, and a red flag in review.
  sudo find "${dir}" -perm /6000 -type f -exec chmod u-s,g-s {} + 2>/dev/null || true

  # Directories 0755; files readable by all and writable by the owner, with
  # whatever execute bits debootstrap already set **preserved**.
  #
  # This was wrong first time: `chmod 0644` over every file clears the execute bit
  # before a later `-perm /111` pass can find anything, so every binary in the
  # image — `env`, `socat`, `curl`, all of them — landed non-executable and proot
  # refused to start (`'/usr/bin/env' is not executable`). The modes are additive
  # here instead: set the bits we want, do not clear the ones that matter.
  sudo find "${dir}" -type d -exec chmod 0755 {} +
  sudo find "${dir}" -type f -exec chmod u+rw,go+r {} +

  # bash is checked by ProotLauncher.isReady(); assert it explicitly rather than
  # trusting the pass above to have preserved it.
  sudo chmod 0755 "${dir}/bin/bash"

  log "packing ${name}"
  # Owned by root inside (proot's -0 maps the app uid to root). gzip -9, not zstd:
  # the Android side extracts with a Kotlin tar path that must not need a zstd binary.
  sudo tar --numeric-owner --owner=0 --group=0 \
    -C "${dir}" -czf "${OUT}/${name}-${ARCH}.tar.gz" .

  sudo chown "$(id -u):$(id -g)" "${OUT}/${name}-${ARCH}.tar.gz"
}

build_tarball debian-core "${CORE_PACKAGES[@]}"
build_tarball debian-extended "${CORE_PACKAGES[@]}" "${EXTENDED_PACKAGES[@]}"

log "checksums"
# Appends: a three-arch release collects all six tarballs into one SHA256SUMS, which
# is what the release carries and what the device verifies against.
( cd "${OUT}" && sha256sum "debian-core-${ARCH}.tar.gz" "debian-extended-${ARCH}.tar.gz" \
    >> SHA256SUMS && sort -u -k2 SHA256SUMS -o SHA256SUMS && cat SHA256SUMS )

# The record that makes a rebuild comparable (§6.1.1).
log "writing BUILD.md"
{
  echo "# Rootfs build record"
  echo
  echo "Generated by \`scripts/build-rootfs.sh --arch ${ARCH}\`."
  echo
  echo "| | |"
  echo "|:--|:--|"
  echo "| Android ABI | \`${ARCH}\` |"
  echo "| Debian arch | \`${DEB_ARCH}\` |"
  echo "| Suite | \`${SUITE}\` (pinned, not \`stable\`) |"
  echo "| Mirror | \`${MIRROR}\` |"
  echo "| debootstrap | $(debootstrap --version 2>/dev/null || echo unknown) |"
  echo "| Built | $(date -u +%Y-%m-%dT%H:%M:%SZ) |"
  echo
  echo "## debian-core"
  echo
  echo '```'
  echo "${CORE_PACKAGES[*]}"
  echo '```'
  echo
  echo "## debian-extended"
  echo
  echo '```'
  echo "${EXTENDED_PACKAGES[*]}"
  echo '```'
  echo
  echo "## Resolved versions"
  echo
  echo "The exact versions in this build. Two runs months apart will differ: Debian"
  echo "moves, and that is the trade for not vendoring the tarball in git."
  echo
  echo '```'
  sudo chroot "${WORK}/debian-core" dpkg-query -W -f='${Package} ${Version}\n' 2>/dev/null | sort || echo "(chroot unavailable)"
  echo '```'
} > "${OUT}/BUILD-${ARCH}.md"
sudo chown "$(id -u):$(id -g)" "${OUT}/BUILD-${ARCH}.md"

log "done"
ls -lh "${OUT}"
