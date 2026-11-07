# `third_party/` — vendored native dependencies

Built from source in this tree, not fetched. Both are in the trust path of the
thing that fakes root inside the container, so the source, the compiler flags and
the version are all reviewable and pinned.

| Tree | What | Licence | Upstream |
|:--|:--|:--|:--|
| `proot/` | The userspace `chroot`/`mount --bind` (the Debian engine's launcher). | GPL-2.0-**or-later** — compatible with this repo's GPL-3.0 | `proot-me/proot` @ `v5.5.0` |
| `talloc/` | proot's allocator. Not in the NDK. | LGPL-3.0-or-later (Samba) | `talloc` 2.4.2 |

## What is pruned, and why

- `proot/extension/care/` — self-extracting archives (`proot -c`). Needs libarchive
  and is unused. Its entry point `get_care_cli` is `__attribute__((weak))` and its
  only call site is NULL-guarded, so it links cleanly as absent. `extract.h` is kept
  because `cli/cli.c` includes it (for the other weak symbol, also unused).
- `proot/extension/python/` — swig bindings; not a launcher feature.
- `proot/GNUmakefile` — replaced by `proot/CMakeLists.txt`. The makefile assumes host
  `gcc`/`pkg-config`, and its embedded-loader step is not expressible as a source list.
- `talloc/`'s waf build, `lib/replace/*.c` (nothing is replaced on bionic),
  and the tests.

## Local changes to proot (re-apply on any upstream bump)

proot is upstream code, but Android's app seccomp policy breaks it as shipped, so
this tree carries a fix. A future `proot` upgrade must carry it forward:

- **`syscall/sigsys.c` + `syscall/sigsys.h` (new)** — a SIGSYS handler. Android's
  app seccomp filter uses `SECCOMP_RET_TRAP`, which cancels a syscall *before*
  PRoot's `PTRACE_TRACEME` and outranks it, so PRoot never sees the call. The
  filter blocks the legacy `open(2)`, `access(2)`, `set_robust_list(2)` and
  others that a Debian **x86_64** glibc issues. The handler rewrites the blocked
  syscall into its `*at` form (`open`→`openat`, `access`→`faccessat`, …) and
  restarts it so PRoot translates the path; a blocked call with no `*at` form is
  answered `-ENOSYS`, which glibc treats as "not implemented" and falls back
  from. Without this a guest dies with `terminated with signal 31` (SIGSYS)
  having printed nothing. Upstream proot has no equivalent; Termux's fork does.
- **`tracee/event.c`** — a `case SIGSYS` in **both** event loops dispatches to that
  handler when `siginfo.si_code == SYS_SECCOMP`. There are two handlers:
  `handle_tracee_event` (kernels < 4.8) and `handle_tracee_event_kernel_4_8`
  (kernels >= 4.8). The case was originally only in the first, which is dead on
  any modern device — Android 9's emulator kernel is < 4.8 so it worked there,
  while Android 13 (kernel 5.15) took the 4.8 path, found no case, fell to
  `default:` ("deliver as-is"), and the guest died with signal 31 having printed
  nothing. **Both handlers must carry the case.** The 4.8 handler is the one that
  runs on every current Android.
- **`PROOT_NO_SECCOMP=1` is required at runtime on Android** (set by
  `ProotLauncher`; see `NO_SECCOMP_ENV`). proot's own seccomp-based ptrace
  acceleration (`PTRACE_O_TRACESECCOMP`) collides with Android's app seccomp
  filter and corrupts the SIGSYS rewrite: the restarted syscall is mishandled and
  fails with a bogus errno. Measured on Android 13 from the app's process —
  `mkdir` returns `EROFS` (errno 30) with acceleration on, and succeeds with
  `PROOT_NO_SECCOMP=1`. This is proot's documented workaround; it costs
  performance (falls back to `PTRACE_SYSCALL`), not correctness.
- **`syscall/sysnum.[ch]`** — `translate_sysnum` is made non-static so the handler
  can map the number from `siginfo` (the cancelled call's number is not in the
  register a tracer would normally read).
- **`loader/assembly-*.h` + `loader/assembly.S`** — the embedded loader must use
  `openat`, not `open`, for the same reason. The loader is freestanding, so it
  has no libc to fall back: it needs `PREPARE_ARGS_4`/`OUTPUT_CONTRAINTS_4`
  (arm, x86_64) and a `syscall_4` (x86). arm64 already used `openat`.
- **`CMakeLists.txt`** — adds `syscall/sigsys.c` to the sources, and lists the
  `loader/assembly-*.h` headers in the loader's `DEPENDS` so a change to a
  syscall number actually rebuilds the loader.

## Building

Both are `add_subdirectory`d from `android-core/src/main/cpp/CMakeLists.txt`, so the
NDK toolchain is applied once and `:android-core:assembleDebug` produces them. Each
`CMakeLists.txt` explains the non-obvious parts — the loader embed, `build.h` and
`config.h` generation — inline. Read them before changing anything here.

## The one thing that is not obvious

`proot` is built as a **PIE executable** named `proot.so`, not a shared library. A
`SHARED` library has no entry point and no `PT_INTERP`, so exec'ing it segfaults
immediately. Android extracts a `jniLibs/<abi>/*.so` with mode 0755, which is what
gives the executable bit — the `jniLibs` trick the architecture doc describes (§1.1).
