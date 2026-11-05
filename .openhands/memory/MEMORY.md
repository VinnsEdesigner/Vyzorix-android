# Project memory — vyzorix-android

## What this is
Headless Android agent: a Debian userspace in `proot`, exposed over a cloud
WebSocket. The server half is `vyzorix-update-server`; this repo is the device
half. `doc/ANDROID_SERVICE_ARCHITECTURE.md` is the contract; `AGENTS.md` has the
build/gate details. Read both before structural changes.

## Toolchain (pinned, do not bump)
JDK 21, Gradle 8.7 (wrapper), AGP 8.3.2, Kotlin 1.9.23, KSP 1.9.23-1.0.20,
compileSdk 34, NDK 26.3.11579264, CMake 3.22.1, **targetSdk 28** (pinned — Android
10 denies `execve` in app storage for targetSdk >= 29; no workaround).

`./gradlew gate` = ktlintCheck + detekt + deadcode + testDebugUnitTest + nativeGate.
Native gate needs a compile db: `./gradlew -PexportCompileCommands=true
:android-core:configureCMakeDebug`.

**Sandbox:** JDK/SDK live in the ephemeral root and are wiped on restart. Install:
`openjdk-21-jdk-headless`, then cmdline-tools + platform-34 + build-tools 34.0.0 +
ndk 26.3.11579264 + cmake 3.22.1 into `/opt/android-sdk`. Env:
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/opt/android-sdk`,
`GRADLE_USER_HOME=/tmp/gradle-home`, and `local.properties` with `sdk.dir`.
Emulator has **no KVM** → `x86_64` image, `-accel off`, boot ~80s.

## Modules
- `android-core` — the only module touching Android APIs. PTY broker/reader, the
  filesystem unix-socket bridge with per-session capability tokens, proot launcher,
  permission registry. 44 JVM tests, 26 instrumented.
- `cloud-connector` (Build 3) — the cloud client. Depends on `android-core`.
- `app` — the APK; `LaunchActivity` starts the service.

Tests live in the repo-root `tests/<module>/{unit,instrumented}` tree, never in the
module. Each module's `build.gradle.kts` points its test source set there.

## Build 3 (2026-11-03) — cloud-connector
Decisions: client in `cloud-connector/`; **libv published** (JitPack
`com.github.VinnsEdesigner.libv:libv:1.0.0`); OkHttp 4.12.0 is the one new
third-party dep. See `.openhands/memory/2026-11-03.md` for the full detail.

The wire contracts the device must match, all read off the server at `b3fb0790`:
- **Connect handshake**: query params `sig_timestamp`/`sig_nonce`/`sig_signature`,
  signed over `connect|deviceId|ts|nonce`, **base64url unpadded** (a `+` in a query
  string decodes as a space).
- **Command/config frame**: `command|dispatchId|deviceId|command|ts|nonce|args`,
  `args` is the **exact raw JSON substring** (re-encoding breaks the signature).
- **Device HTTP request**: `UPPER(method)\npath\ndeviceId\nts\nnonce\nbase64(sha256(body))`,
  headers `X-Vyzorix-Timestamp`/`-Nonce`/`-Signature`.
- **PTY binary**: `[1B channel=0x01][1B type][2B BE len][payload]`; DATA/OPEN/RESIZE
  payloads are session-prefixed `[1B idLen][id][data]`. Max payload 64 KiB.
- Config `vyzorix.v` is verified with **libv** against the pinned server key; the
  device re-checks the no-secret invariant.

## Lint/gradle gotchas (cost real time)
- ktlint `MaxLineLength` is 100. `@Suppress("X") // reason` overflows it, and a line
  comment between a KDoc and `@Suppress` is **rejected** — use a bare `@Suppress`
  directly above the declaration.
- A multi-line `catch (\n @Suppress(...)\n e: T,\n)` is reordered by ktlint into
  `Exception: e` (breaks compilation). Suppress at **function** level.
- For a published Kotlin lib, `compileJava` and `compileKotlin` JVM targets must
  match or Gradle fails.
- detekt wants named constants for protocol masks/widths; `@OptIn(ExperimentalSerializationApi::class)`
  is needed on the `Json { }` builder.

## The proot-on-Android PTY defect (FIXED 2026-11-05)

The container terminal was broken because **Android's app seccomp filter kills
PRoot's syscalls**. The filter uses `SECCOMP_RET_TRAP`, which cancels the syscall
*before* PRoot's `PTRACE_TRACEME` and outranks it, so PRoot never sees the call
and cannot translate it. The filter blocks the legacy `open(2)`, `access(2)`,
`set_robust_list(2)` … that a Debian **x86_64** glibc issues; PRoot's own
embedded loader also used `open(2)`. The guest died with `terminated with signal
31` (SIGSYS) having printed nothing.

- **Diagnosis tool**: `siginfo.si_syscall` from `PTRACE_GETSIGINFO` on the SIGSYS
  is the authoritative blocked syscall. `get_sysnum(tracee, CURRENT)` reads the
  *wrong* register at a SIGSYS and reports a stale number — it cost an hour.
- **Fix**: `third_party/proot/syscall/sigsys.[ch]` (new) rewrites the blocked
  syscall to its `*at` form and restarts it; a blocked call with no `*at` form is
  answered `-ENOSYS` (glibc falls back). Loader `open`→`openat`. See
  `third_party/README.md` §"Local changes to proot" for the full list — that
  section must be carried forward on any upstream proot bump.
- **arm64 is unaffected** (glibc uses `openat` there); this is an x86_64-emulator
  issue that nonetheless must be fixed, since the code ships for all ABIs.
- **Regression tests**: `:app`'s `EngineTerminalE2ETest` (4) and
  `ProotFromAppProcessTest` now pass. `ProotFromAppProcessTest` is flaky when run
  right after `EngineTerminalE2ETest` (a `libtinfo.so.6 … Operation not
  permitted` race); it passes standalone. Not yet diagnosed.

### Emulator/toolchain notes for this repo
- Toolchain is installed under `/workspace/toolchain` (survives the sandbox wipe
  of everything outside `/workspace`): JDK 21, Android SDK 34 + build-tools 34.0.0
  + NDK 26.3.11579264 + cmake 3.22.1 + emulator + `system-images;android-28;default;x86_64`.
  Source `/workspace/env.sh`; `GRADLE_USER_HOME=/workspace/gradle-home`.
- Build the APK for the emulator with `-ProotfsAbi=x86_64` (the bundled rootfs is
  arm64 otherwise). Fetch it from the public `VinnsEdesigner/gz-_ASSETS` release
  into `debian-engine/rootfs/dist/`; the asset repo is private, so use `$GIT` and
  the assets API (`Accept: application/octet-stream`) rather than the raw URL.
- The `emulator` + `adb install` wedge under repeated install/uninstall; `adb emu
  kill` then relaunch (`-wipe-data` if needed). Gradle's device discovery also
  races the adb server — `./gradlew --stop` + restart helps.
- Tests run one class at a time reliably; run the whole suite in one go sparingly.
