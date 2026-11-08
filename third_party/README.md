# `third_party/` — vendored native dependencies

Built from source in this tree, not fetched. Both are in the trust path of the
thing that fakes root inside the container, so the source, the compiler flags and
the version are all reviewable and pinned.

| Tree | What | Licence | Upstream |
|:--|:--|:--|:--|
| `proot/` | The userspace `chroot`/`mount --bind` (the Debian engine's launcher). | GPL-2.0-**or-later** — compatible with this repo's GPL-3.0 | `termux/proot` @ `v5.1.107.102` |
| `talloc/` | proot's allocator. Not in the NDK. | LGPL-3.0-or-later (Samba) | `talloc` 2.4.2 |

## Why the Termux fork, not upstream proot-me

Upstream proot assumes a normal Linux, where a process is not confined by an
Android app seccomp filter. Android's `untrusted_app` policy uses
`SECCOMP_RET_TRAP`, which **cancels a syscall before proot's `PTRACE_TRACEME` can
see it**, and it also traps the "avoider" syscall number proot writes when it
emulates a call itself. Upstream therefore needs a hand-written SIGSYS handler to
survive at all, and even with one it broke here in ways that only showed up on a
device (see "What upstream got wrong" below).

`termux/proot` is the same code base with the fixes for exactly this environment —
Termux runs under the same Android filter — so it is the supported base for an
Android target. It is a fork, not a different program: the CLI, the loader and the
extension model are upstream's.

## What is pruned, and why

- `GNUmakefile` — replaced by `CMakeLists.txt`. The makefile assumes host
  `gcc`/`pkg-config`/`talloc.pc`, none of which exists for an NDK build, and its
  embedded-loader step is not expressible as a source list.
- The `tests/`, `doc/`, `.github/` trees and the top-level build files are not
  vendored; only `src/`'s contents (flattened to this directory) plus `COPYING`.

## What upstream proot-me got wrong here (why we switched)

Measured on the Android 13 (API 33) emulator, from the app's own process (Seccomp
2), before the switch. Each was a real defect an operator would hit:

- **`cd` / `fchdir` / `chroot` returned `ENOSYS`.** proot emulates these fully by
  rewriting the syscall number to the "avoider" (`-1`). Android's filter traps an
  unknown number too, so the cancellation itself raised SIGSYS; the handler's
  `default:` answered `-ENOSYS` and clobbered the emulated result. `mkdir` worked
  (it is rewritten to an allowed `mkdirat`) which is what made it look like a
  `chdir`-specific bug rather than a SIGSYS-dispatch one.
- **`poll(2)` segfaulted the guest.** The rewrite to `ppoll` allocates a
  `struct timespec` on the tracee's stack; the stack pointer was not restored
  correctly across the trap. `select(2)` worked, so a lot of code appeared fine.
- **`getaddrinfo(3)` segfaulted** for a second reason: the rootfs shipped no
  `/etc/hosts`, and glibc's resolver dereferences it unconditionally. That is a
  rootfs defect, fixed in `scripts/build-rootfs.sh`, but it presented as a proot
  crash until isolated.

Termux's fork handles all three: `chdir` returns 0, `poll` returns, and
`getaddrinfo` resolves (with `/etc/hosts` present). Verified on the API 33
emulator through the production terminal path, together with `curl` (HTTP 200) and
`git ls-remote` over HTTPS.

## Local changes to this vendored tree

Small, and all documented at the point of change:

- **`extension/ashmem_memfd/ashmem_memfd.c`** — add `#include <string.h>`. Upstream
  relies on a transitive include from the host headers; the NDK's do not provide
  it, and C99+ makes the implicit declaration a hard error.
- **`CMakeLists.txt` + `cmake/gen-loader-info.cmake`** — the NDK port: the source
  list, the `build.h` generation (compile-time instead of upstream's run-a-probe),
  the freestanding loader build + `objcopy` embed, and the arm64 pokedata-offset
  generation. See the comments in `CMakeLists.txt`.

**`PROOT_NO_SECCOMP=1` must NOT be set with this fork.** It was required by
upstream (its acceleration corrupted the SIGSYS rewrite), but Termux's seccomp path
is the *mechanism* by which it handles the trap: with the acceleration disabled,
Termux proot mishandles its own rewrites and `rename(2)` fails with `ENOSYS`
(measured — `os.rename` in the container). The launcher therefore sets only
`PROOT_TMP_DIR`.

## A proot rename can report ENOENT after it succeeded

Independent of the above: when the destination of a `rename(2)` already exists, this
proot can move the file and still report `ENOENT`. It is not fatal to proot, but it
breaks anything that treats a rename failure as fatal — npm's content-addressable
store aborts an install on it (~25% of its writes failed, measured). The container's
node prelude treats "ENOENT but the destination exists" as success; see
`doc/NODE_IN_CONTAINER.md`.

## Building

Both trees are `add_subdirectory`d from `android-core/src/main/cpp/CMakeLists.txt`,
so the NDK toolchain is applied once and `:android-core:assembleDebug` produces
them. Each `CMakeLists.txt` explains the non-obvious parts — the loader embed,
`build.h` generation, the pokedata offset — inline. Read them before changing
anything here.

## The one thing that is not obvious

`proot` is built as a **PIE executable** named `libproot.so`, not a shared library.
A `SHARED` library has no entry point and no `PT_INTERP`, so exec'ing it segfaults
immediately. Android extracts a `jniLibs/<abi>/*.so` with mode 0755, which is what
gives the executable bit — the `jniLibs` trick the architecture doc describes
(§1.1). AGP also only packages `lib*.so`, so the name is load-bearing twice over.
