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

## Layer 2 is complete (2026-11-05)

The container terminal works and Layer 2's missing pieces are built.

- **`android-bin` is C++, not Go** (design change; the repo has two languages:
  Kotlin/Gradle and C++). `debian-engine/android-bin/` — its own Makefile, built for
  **glibc** with the GNU cross-compilers (`aarch64-linux-gnu-g++`,
  `arm-linux-gnueabihf-g++`), never the NDK. `apis.txt` is the single source: one
  generator pass emits both `api_catalog.cpp` and the symlinks. `ApiCatalogTest`
  (android-core unit) fails the gate if `apis.txt` drifts from the router's
  registrations. `make check` runs the JSON tests. The installable unit is
  `build/bin/` (binary + symlinks together).
- **Extended tier**: `RootfsInstaller.ensureExtended()`, checksum from
  `ROOTFS_EXTENDED_SHA256` (read from the build's SHA256SUMS), marker
  `files/extended.installed`. Empty checksum = tier disabled, never unverified.
- **tmux reattach**: `loginShellArgs(tmuxSession=...)` wraps the shell in
  `tmux new-session -A -s vyzorix-<sessionId>`, gated on `hasTmux`. The
  `ServicePtySessionPort.shellRequest` seam now takes `(sessionId, token)`.
- **Native gate split**: clang-format covers every source we wrote; clang-tidy runs
  only on NDK-built sources (android-bin is not in AGP's compile DB — tidy there
  fails on include paths, not real problems).
- **Toolchain scripts**: `scripts/install-toolchain.sh` (one command, persistent
  prefix, re-runnable), `scripts/run-emulator.sh` (one-command boot + wait), both
  documented in the root `README.md` and `AGENTS.md`. `install-toolchain.sh` also
  installs `g++-*` cross-compilers and retries apt on a 503 (the mirror rate-limits).
- **Not done / next**: Layer 3 (`app-runtime/` — supervisor, `app-*`, inotify). The
  rootfs `debian-extended` tarball is published; a device fetches it on demand.

## Layer 3 — built, one device-only defect open (2026-11-05)

Layer 3 is written and its host tests all pass, but it is **not yet working on a
device**: the supervisor starts and binds its socket, then SIGSEGVs ~200ms later.

**What exists** (`app-runtime/`, `native/lib/`):
- `native/lib` — shared C++: `json`, `frame` (the repo's `[4B len][1B type][payload]`
  framing over unix sockets), `apps` (the `apps.json` registry, atomic save, PID
  start-time tokens from `/proc/<pid>/stat` field 22, parsed from the last `)`).
- `supervisor/` — `Manager` (policy: autostart, backoff, give-up, recycled-pid
  check) over an injected `Host` (the only file with fork/exec), plus a control
  socket whose `HandleRequest` is pure JSON-in/JSON-out. 14 + 15 host tests.
- `cli-wrappers/` — one `app-tool` binary, nine `app-*` symlinks (same pattern as
  android-bin). `app-list` prints the registry JSON; `app-expose` refuses a
  non-loopback bind without a token.
- `scripts/build-layer3.sh` — cross-compiles all three tools per ABI into
  `app-runtime/dist/layer3-<abi>.tar.gz`, **statically linked** (the rootfs is
  Debian 12 / glibc 2.36; the host toolchain is 2.41 — a dynamic binary fails to
  load on device with "GLIBC_2.38 not found").
- Layer 1: `ProotLauncher` binds `files/apps:/home/apps`, `supervisorArgs()`;
  `Layer3Installer` unpacks the asset into `overlay/usr-local` (so a rootfs
  replacement leaves it); the service spawns the supervisor and restarts it on exit.

**FIXED 2026-11-06.** The SIGSEGV had two causes, and the first hid the second.
1. `./gradlew :app:assembleDebug` never rebuilt `app-runtime/dist/layer3-<abi>.tar.gz`
   — `stageLayer3` only copied the existing file. So every device test ran the same
   stale binary while the host tests passed, which made a series of isolation
   experiments look conclusive when they were all testing one unchanged asset.
   `buildLayer3` is now a Gradle prerequisite of staging.
2. **`accept` had no SIGSYS conversion** — the filter traps it, the handler's default
   answered `-ENOSYS`, and a connected client then hung forever ("supervisor up but
   dead"). Now `accept` -> `accept4`. Also fixed: a double-close in `ServeOne`
   (its `Connection` already owned the fd) and SIGPIPE at its default, which let a
   client disconnecting mid-write kill the supervisor.
   Verified live: create/start/list/logs/expose all work, and killing an app makes
   the supervisor restart it (pid 6879 -> 6910).

**Also built 2026-11-06:** inotify live-reload (`native/lib/watch.cpp` — watch the
*directory*, not `apps.json`, because Save renames and kills a file watch); log
rotation (`native/lib/log.cpp` — copy-then-truncate, safe only because the log is
opened O_APPEND); and the `APP_START`/`APP_STOP`/`APP_RESTART` executors
(`cloud-connector/.../AppCommands.kt`, over `AppControlClient` using
`LocalSocket` in the FILESYSTEM namespace — the NIO unix-domain API is Java 16+ and
not on Android). `AppCommandE2ETest` verifies the whole chain on the emulator.

**Historical note on the original defect.** `proot info: vpid 1: terminated with signal 11` ~200ms after
`supervisor: ready on /home/apps/supervisor.sock`. Started by hand (`run-as ...
proot ... supervisor`) it runs forever, so it is specific to the **ART process**,
where Android's seccomp filter is active and proot's SIGSYS handler runs.
Ruled out: the phantom-process killer (Android 9 has none), `PROOT_NO_SECCOMP=1`,
the extended-tier install racing, and the select/poll choice (a nanosleep-only loop
crashes the same way). The last SIGSYS before the crash is a blocked `mkdir`, so
**suspect the SIGSYS handler's rewind/restart path** (`third_party/proot/syscall/
sigsys.c`). Note the handler is required: without it nothing execs at all (open,
access, poll, mkdir, set_robust_list are all trapped by the filter).

**Device-only bugs fixed this session** (each found by running it, not reading):
- `TarGz` symlink branch used `deleteRecursively` when a directory was in the way,
  which destroyed `usr/lib64` and left `usr/lib64 -> usr/lib64` (an infinite
  self-loop) — every dynamic binary then failed with "ld-linux.so not found".
- `TarGz.extract` treated an empty checksum as a mismatch, rejecting every bundled
  asset.
- The child opened its log after `chdir`, so a missing app dir exited 127 with an
  empty log.
- Container binaries must be **static** (glibc mismatch), and `build-layer3.sh`
  now fails if any is dynamic.

**Toolchain traps hit this session** (now handled in `install-toolchain.sh`):
- `cp -a` of the JDK preserves symlinks into `/etc/java-21-openjdk` and
  `/etc/ssl/certs/java/cacerts`, which the sandbox wipes. Use `cp -aL`, and run
  ca-certificates-java's postinst with the prefix's `java` on PATH to generate
  `cacerts` — without it every TLS call (Robolectric's jar fetch) fails with PKIX.
- `apt-get update` is needed after a restart before `apt-get install` finds
  packages; the mirror rate-limits (503) so retry.
- `scripts/native-gate.sh` now derives its clang-tidy set from AGP's compile
  database instead of a hand-list, so a module is tidy'd only when the DB describes
  how to build it (the container tools are glibc-built and are not in it).
