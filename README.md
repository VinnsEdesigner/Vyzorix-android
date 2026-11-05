# Vyzorix — Android

A headless Android agent. It runs a Debian userspace inside `proot`, exposes it
over a cloud WebSocket, and gives an operator a terminal (and, later, USB and app
control). This repository is the **device half**; the server half lives in
`vyzorix-update-server`.

> **Read `doc/ANDROID_SERVICE_ARCHITECTURE.md` first.** It is the authority for
> everything structural, and it records *why* the awkward constraints below exist.
> `AGENTS.md` has the build, gate and toolchain detail. If a document here
> disagrees with the architecture doc, the architecture doc wins.

---

## Quick start

```bash
# 1. One command installs the whole toolchain into a persistent prefix.
scripts/install-toolchain.sh
source /workspace/toolchain/env.sh

# 2. Point Gradle at the SDK, then build and check.
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew gate                      # the gate: format, lint, dead code, tests, native

# 3. Boot the test emulator (one command; waits until it is usable).
scripts/run-emulator.sh

# 4. Run the on-device tests (the container terminal lives here).
./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64
./gradlew :android-core:connectedDebugAndroidTest
```

That is the whole loop. The two scripts are the only setup you need; the rest of
this file explains them and the layout.

---

## The toolchain scripts

Both scripts live in `scripts/` and are the supported way to set up and run
anything. They exist because **everything outside `/workspace` is wiped when the
sandbox restarts**, so a toolchain installed at the conventional paths
(`/opt/android-sdk`, `/usr/lib/jvm`) does not survive a session; these install into
a persistent prefix instead.

### `scripts/install-toolchain.sh`

Installs everything this repository needs, into a persistent prefix, in one
command. **Re-runnable**: each step is skipped when its artifact already exists, so
a second run is a no-op (~3 s) and a run after a partial failure resumes.

```bash
scripts/install-toolchain.sh [--prefix DIR] [--skip STEP]... [--rootfs-abi ABI]...
```

| Step | What it installs | Why the repo needs it |
|:--|:--|:--|
| `jdk` | JDK 21 | Gradle/AGP 8.3.2 build the Kotlin. Copied into the prefix, not symlinked — the JVM it comes from is in the ephemeral root. |
| `android-sdk` | cmdline-tools, platform 34, build-tools 34.0.0, NDK 26.3.11579264, CMake 3.22.1, platform-tools | Compiles `:android-core` (NDK/CMake) and `:app` (Kotlin). |
| `emulator` | the emulator, `system-images;android-28;default;x86_64`, and the `x86test` AVD | Runs the instrumented suite. The image and AVD are the ones `AGENTS.md` prescribes. |
| `rootfs` | the Debian userspace tarballs, from the public release | The container's userspace. Verified against the release's own `SHA256SUMS`. |
| `engine-build` | `debootstrap`, `qemu-user-static`, `binfmt-support`, and the `binfmt_misc` handlers | Rebuilds a rootfs for **any** architecture (`scripts/build-rootfs.sh`). Cross-arch needs a registered `binfmt_misc` handler, not just qemu. |
| `cross-compilers` | `gcc-aarch64-linux-gnu`, `gcc-arm-linux-gnueabihf` | Builds the Layer 3 supervisor / CLI wrappers for the Debian userspace. |

Options: `--prefix DIR` changes the install root (default `/workspace/toolchain`);
`--skip STEP` omits a step; `--rootfs-abi ABI` chooses which rootfs tarballs to
fetch (default `x86_64 arm64` — the emulator's and the one the APK bundles).

On completion it writes `<prefix>/env.sh`, which exports `JAVA_HOME`,
`ANDROID_HOME`, `GRADLE_USER_HOME` and the `PATH` entries. **Source it in every
shell**:

```bash
source /workspace/toolchain/env.sh
```

There is **no Go** and no other language toolchain: the device is Kotlin (Gradle)
and C++ (the NDK), and the container is a Debian tree.

### `scripts/run-emulator.sh`

Boots the test emulator and waits until it is actually usable — `adb` connected,
`sys.boot_completed` set, and the package service ready to accept installs.

```bash
scripts/run-emulator.sh [--avd NAME] [--install APK]... [--wipe] [--fg]
```

The flags are not free choices. This host has **no KVM**, so hardware acceleration
is impossible: the script uses the `x86_64` image (the arm64 image refuses to boot
on an x86_64 host) with `-accel off`, and a full boot takes **1–3 minutes**. That
is normal, not a hang.

- `--install APK` installs an APK once booted (repeatable).
- `--wipe` discards user data first. Use it when `adb install` starts failing with
  a package-service error, which a long-lived emulator eventually does.
- `--fg` keeps the emulator in the foreground.

It is idempotent: if an emulator is already connected it reuses it and just waits.

Stop the emulator with `adb emu kill`.

### `scripts/build-rootfs.sh`

Rebuilds the Debian rootfs tarballs (one per architecture) from `debootstrap`.
This is the Layer 2 asset pipeline; the released tarballs are what
`install-toolchain.sh` fetches. See `debian-engine/rootfs/README.md`.

```bash
scripts/build-rootfs.sh --arch arm64        # most current devices
scripts/build-rootfs.sh --arch armeabi-v7a  # older 32-bit devices
scripts/build-rootfs.sh --arch x86_64       # emulators, x86 tablets
```

---

## Build and test

```bash
./gradlew projects                    # verify module wiring
./gradlew :android-core:assembleDebug # build the core library
./gradlew :app:assembleDebug          # build the APK
./gradlew gate                        # THE GATE — run before every commit
```

The emulator APK needs the x86_64 rootfs bundled, so pass the ABI:

```bash
./gradlew :app:assembleDebug -ProotfsAbi=x86_64
./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64
```

`./gradlew gate` is what CI runs and what a change must pass: ktlint, detekt,
Android lint, the dead-code gate, unit tests, and the native gate (clang-format +
clang-tidy from the NDK). The native gate needs a compile database once:

```bash
./gradlew -PexportCompileCommands=true :android-core:configureCMakeDebug
```

---

## Layout

```
android-core/     Layer 1 — the OS-facing library: foreground service, the unix
                  socket bridge, per-session tokens, the PTY broker (NDK), the
                  proot launcher. The only module that touches Android APIs.
cloud-connector/  Layer 4 — the cloud client: enroll, device WebSocket, config,
                  commands, telemetry, the terminal relay.
app/              the APK: manifest, permissions, and the engine's end-to-end test.
debian-engine/    Layer 2 assets — the rootfs tarballs and android-bin.
doc/              the architecture, the build contracts, and the spike results.
tests/            all tests, split per module (unit/ and instrumented/).
third_party/      vendored native deps (proot, talloc), built by our own CMake.
```

The four-layer design, the fd-capability model, and the PTY wire contract are in
`doc/ANDROID_SERVICE_ARCHITECTURE.md`. `doc/README.md` indexes the rest.

---

## The one rule that surprises people

`targetSdk` is pinned at **28** and must not be raised. Android 10 denies
`execve()` on files inside an app's home directory for any app targeting API 29+,
and the engine execs binaries from app storage. There is no userspace workaround.
The consequence is that the app is **sideload-only** — Play requires a recent API
level. See `doc/ANDROID_SERVICE_ARCHITECTURE.md` §1.1 before touching it.

---

## Licence

GPL-3.0 (see `LICENSE`). The vendored `proot` is GPL-2.0-or-later and `talloc` is
LGPL-3.0-or-later; both are compatible. `libv`, the one external library the build
consumes, is Apache-2.0.
