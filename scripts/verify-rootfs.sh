#!/usr/bin/env bash
#
# Verify a built rootfs tarball without rebuilding it.
#
#   scripts/verify-rootfs.sh                    # every tarball in dist/
#   scripts/verify-rootfs.sh debian-core-arm64.tar.gz
#
# build-rootfs.sh fixes and asserts these invariants at build time. This is the
# independent check: it reads a finished artifact — including one built on another
# machine — in seconds where a rebuild takes twenty. Read-only, no root needed.
#
# It exists because two defects shipped in every tarball while the build reported
# success, and only surfaced inside the container: `/dev/null` was a regular file
# (so `> /dev/null` grew a file and apt failed), and `/tmp` was 0755 (so apt could
# not write its temp files). Both are cheap to check and expensive to find.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${ROOT}/debian-engine/rootfs/dist"

fail=0
check() { # description, ok(0/1)
  if [ "$2" -eq 0 ]; then
    printf '  ok    %s\n' "$1"
  else
    printf '  FAIL  %s\n' "$1"
    fail=1
  fi
}

# The mode and type of a single entry, as tar records it: "drwxr-xr-x" etc.
# Field 6 is the name — NOT $NF, which for a symlink is its target. `tar t` is used
# rather than `tar x` so nothing is written.
entry() { # tarball, path-without-leading-dot-slash
  tar tvzf "$1" 2>/dev/null | awk -v p="./$2" '$6 == p || $6 == p"/" { print; exit }'
}

verify() { # tarball-path
  local tarball="$1"
  local name
  name="$(basename "$tarball")"
  printf '\n== %s\n' "$name"

  if [ ! -f "$tarball" ]; then
    printf '  FAIL  not found\n'
    fail=1
    return
  fi

  # /dev/null and /dev/shm must NOT be present. A tar cannot carry a usable device
  # node here (the extractor throws on type '3'; mknod needs CAP_MKNOD), and a plain
  # file breaks apt. The launcher binds the real /dev over the directory instead.
  local nullentry
  nullentry="$(entry "$tarball" "dev/null")"
  check "/dev/null is not shipped (the launcher binds the real /dev)" \
    "$([ -z "$nullentry" ] && echo 0 || echo 1)"
  if [ -n "$nullentry" ]; then
    printf '        found: %s\n' "$nullentry"
    printf '        a regular file here breaks apt and grows on every write.\n'
  fi

  local shmentry
  shmentry="$(entry "$tarball" "dev/shm")"
  check "/dev/shm is not shipped (the launcher binds the real /dev)" \
    "$([ -z "$shmentry" ] && echo 0 || echo 1)"

  # These are correct as symlinks and needed before any bind; proot rewrites them.
  local link
  for link in fd stdin stdout stderr ptmx; do
    local e
    e="$(entry "$tarball" "dev/${link}")"
    case "$e" in
      l*) check "/dev/${link} is a symlink" 0 ;;
      "") check "/dev/${link} is present" 1 ;;
      *)  check "/dev/${link} is a symlink" 1; printf '        found: %s\n' "$e" ;;
    esac
  done

  # /tmp must be 1777. 0755 breaks apt, which drops privilege; the sticky bit stops
  # one user deleting another's temp file, so it is not optional.
  local tmpline tmpmode
  tmpline="$(entry "$tarball" "tmp")"
  tmpmode="$(printf '%s' "$tmpline" | cut -c1-10)"
  check "/tmp is 1777" "$([ "$tmpmode" = "drwxrwxrwt" ] && echo 0 || echo 1)"
  if [ "$tmpmode" != "drwxrwxrwt" ]; then
    printf '        found: %s (expected drwxrwxrwt)\n' "${tmpmode:-<missing>}"
  fi

  # ProotLauncher.isReady() runs bash, so it must be present and executable. Debian's
  # merged-usr layout puts the real binary at usr/bin/bash with bin -> usr/bin, so
  # both spellings are accepted; a mode pass that clears the execute bit leaves a
  # container that will not start.
  local bashline
  bashline="$(entry "$tarball" "usr/bin/bash")"
  [ -n "$bashline" ] || bashline="$(entry "$tarball" "bin/bash")"
  case "$bashline" in
    -*x*|l*) check "/bin/bash is executable" 0 ;;
    *) check "/bin/bash is executable" 1; printf '        found: %s\n' "${bashline:-<missing>}" ;;
  esac

  # No setuid/setgid: meaningless under proot and a review red flag.
  local setid
  setid="$(tar tvzf "$tarball" 2>/dev/null | awk 'substr($1,4,1)=="s" || substr($1,7,1)=="s"' | wc -l)"
  check "no setuid/setgid entries" "$([ "$setid" -eq 0 ] && echo 0 || echo 1)"
}

if [ "$#" -gt 0 ]; then
  for a in "$@"; do
    case "$a" in
      /*) verify "$a" ;;
      *)  verify "${DIST}/${a}" ;;
    esac
  done
else
  shopt -s nullglob
  found=("${DIST}"/debian-*.tar.gz)
  shopt -u nullglob
  if [ "${#found[@]}" -eq 0 ]; then
    printf 'no tarballs in %s\n' "$DIST" >&2
    printf 'build one first: scripts/build-rootfs.sh --arch arm64\n' >&2
    exit 2
  fi
  for t in "${found[@]}"; do verify "$t"; done
fi

printf '\n'
if [ "$fail" -eq 0 ]; then
  printf 'all rootfs invariants hold\n'
else
  printf 'FAILED: a rootfs invariant is broken (see above)\n'
fi
exit "$fail"
