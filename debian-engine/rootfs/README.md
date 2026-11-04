# `debian-engine/rootfs/` — the container's Debian userspace

The tarballs here are **build artifacts, not repo files** (`dist/` is gitignored).
Build them with `scripts/build-rootfs.sh`.

```
scripts/build-rootfs.sh --arch arm64          # the fleet target
scripts/build-rootfs.sh --arch amd64          # for engine work on an x86_64 emulator
```

Produces, in `dist/`:

| File | What |
|:--|:--|
| `debian-core.tar.gz` | The floor — shell, POSIX userland, TLS trust, `socat`. **Bundled in the APK** and extracted on first boot. |
| `debian-extended.tar.gz` | `git`, `python3`, `tmux` and friends. Downloaded on demand. |
| `SHA256SUMS` | Verified by the device before extracting. |
| `BUILD.md` | The build record: suite, mirror, package sets, and the exact resolved versions. |

`doc/ANDROID_SERVICE_ARCHITECTURE.md` §6.1.1 is the spec — what is in each tarball and
why, the size budget, the required files and modes. Read it before changing the
package lists.

## Building a foreign architecture needs binfmt, not just qemu

`debootstrap --arch=arm64` on an x86_64 host needs to *execute* arm64 binaries during
its second stage. `qemu-user-static` alone is not enough: the kernel must have a
`binfmt_misc` handler registered for the target ELF, or every arm64 binary fails with
`Exec format error` and the build stops with no useful message.

Check, and register if missing:

```bash
cat /proc/sys/fs/binfmt_misc/qemu-aarch64        # present => fine
sudo mount -o remount,rw /proc/sys/fs/binfmt_misc
sudo sh -c 'echo ":qemu-aarch64:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00:\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff:/usr/bin/qemu-aarch64-static:F" \
  > /proc/sys/fs/binfmt_misc/register'
```

`binfmt_misc` is often mounted **read-only** inside a container, in which case the
remount is the part that matters — and if the remount is refused, cross-arch builds
are simply not possible in that sandbox and the tarball must be built elsewhere.

A quick way to prove it works before starting a 10-minute build:

```bash
sudo chroot <any-extracted-rootfs> /bin/sh -c 'echo ok'    # prints ok => binfmt is live
```

## Architecture matters, and it is per-tarball

The rootfs is a tree of native binaries, so there is **one tarball per architecture** —
unlike `libproot.so`, which is one program compiled for three ABIs. An arm64 device
cannot run an amd64 rootfs: `bin/bash` will exist and fail to execute, which reads as a
corrupt rootfs rather than a wrong-arch one.

The fleet is **arm64** (Nokia C22, Unisoc SC9863A). Only `--arch arm64` ships.

## The two things that will look like bugs

1. **`/bin`, `/sbin`, `/lib` are symlinks to `usr/`.** Debian's merged-usr layout.
   `bin/bash` is really `usr/bin/bash`. `File(rootfs, "bin/bash").exists()` follows
   symlinks, so this is fine — but a `tar -t | grep '^./bin/bash$'` will not find it,
   and neither will anything that does not resolve links.
2. **`/opt` and `/usr/local` are deliberately empty.** The writable overlay binds over
   them (§6.1). Content shipped there would be shadowed.
