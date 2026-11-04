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
