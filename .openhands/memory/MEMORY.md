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

## 58 bridge APIs, and the catalogue (2026-11-06)

The bridge served 6 APIs; it serves **58**, all called by `ApiSmokeTest`.

- **`ApiCatalogue` is the source of truth** for registrations. The service registers
  from it, `ApiCatalogTest` reads it, and `scripts/sync-apis-txt.py` writes
  `apis.txt` from it. Before this, the registration chain, `apis.txt` and the test
  were three lists that had to agree with nothing making them — survivable at 6,
  silently broken at 50.
- **`ApiSmokeTest` calls every API** and asserts an outcome rather than an internal
  error, valid JSON, and the permission rule both ways. It immediately found three
  real bugs: `PackageInfo.longVersionCode` is API 28 (minSdk 26) — a
  NoSuchMethodError on a 26/27 device; `NetworkInterface.getNetworkInterfaces()`
  returns an `Enumeration`, not an `Iterable`; `StorageVolume.getDirectory()` is
  API 30. **Run it before claiming an API works.**
- **Adding an API**: write `XxxApi.kt` with `const val NAME`, add one line to
  `ApiCatalogue.handlers`, run `python3 scripts/sync-apis-txt.py`. If it needs a
  permission, add the registry entry too or the router refuses it with no
  explanation.
- **Tags**: `PROTECTION_DANGEROUS` exists and is used; `op:write` does **not** exist
  yet because no API writes — the dead-code gate fails on a constant nothing uses,
  and that is correct. Add it with the first write API.
- **Deliberately absent**: subscriber identifiers (IMEI/IMSI/number) and any write
  API. Both are recorded in the files where they would otherwise be added.
- **Robolectric is on `:android-core`'s test classpath but not `:cloud-connector`'s**
  — a unit test needing an Android runtime must live in `:android-core`, or the
  module needs the dependency added.

## The 58 APIs are now tested on the device (2026-11-06)

`ApiSmokeTest` (Robolectric) is not enough — it never touches proot, the socket
bind, or the C++ client. `:app`'s **`ApiLiveE2ETest`** runs every API the way an
operator does: `proot -> android-bin <api> -> /tmp/android.sock -> the bridge`,
and it is green. Getting it green found four real defects, three in production
code:

- **`android-bin` prints the result directly, with no `{"ok","result"}` envelope.**
  `BridgeClient::Call` unwraps it and `main` prints the inner string. A *failure*
  is `"<api>: <kind>: <detail>"` on stderr (PTY merges streams). The test asserts
  transport: JSON on stdout, or a structured `forbidden`/`internal` refusal, both
  of which are answers. Only `unknown_api` is a failure (catalogue drift).
- **The service never published its terminal port in bridge-only mode.**
  `cloudLink?.attachPtyPort(buildTerminalPort())` — a safe call short-circuits its
  *arguments*, so with no cloud link the port was never built. Build it first, then
  attach.
- **A shell that exits on its own leaked its session.** `ServicePtySessionPort`'s
  `onExit` removed the port entry but never called `broker.close`, so the registry
  kept the session and its per-device count. The cap is **4**; four self-exits (a
  startup death counts) exhausted it and every later terminal was refused. Fixed in
  `onExit`; both cleanup paths tolerate the other running first.
- **tmux cannot wrap the terminal on-device — SELinux.** The tmux *client* resolves
  its own tty name, which reads the `devpts` directory; `untrusted_app` is denied
  (`avc: denied { read } … comm="tmux: client" dev="devpts"`). The client exits
  without attaching, so the terminal echoes input and answers nothing. A bare
  `bash --login` never resolves its tty name, which is why the same PTY works
  without it. **Gated off** behind `TMUX_REATTACH_ENABLED = false` (the code and its
  unit tests stay; reattach is unimplemented, a reconnect starts a fresh shell).
- **proot's `realpath()` fails on a trailing slash** (`/tmp/` -> ENOENT, though
  `stat`/`lstat`/`access` are fine). tmux's compiled default socket list is
  `"$TMUX_TMPDIR:" _PATH_TMP` = `/tmp/`, which it then drops — hence "no suitable
  socket path". `loginShellArgs` now sets `TMUX_TMPDIR=/tmp` (no slash) whenever it
  wraps in tmux. This is a genuine proot bug, not worked around in tmux.

**Test technique that matters**: a shell is occasionally flaky to start (proot's
SIGSYS race), so `runInContainer` retries a command on up to **3 fresh shells**
before failing — one unlucky shell must not fail a 57-shell sweep. `/proc/loadavg`
is denied even to `adb shell` on the emulator, so `load_average` correctly answers a
structured `internal` error; the test treats that as an answer, not a failure.

## Terminal reattach — device-side persistence (2026-11-06)

A session now **outlives the socket** on both sides. Neither a dropped device
socket nor a dropped operator socket ends it; only an explicit `pty_close` or the
idle TTL does. This is the device-side persistence model, chosen over tmux because
tmux cannot work here (SELinux denies its client the devpts read) and because the
app already owns the PTY master fd.

**Device** (`0898a46`): `TerminalRelay.detachAll` replaces `closeAll` on
disconnect; output is buffered up to 256 KiB (oldest dropped) and flushed on
reattach; a detached session is reaped after 10 min; a failed socket write
detaches instead of closing; input while detached is dropped. `CloudConnection`
detaches on disconnect, reaps on connect, and still `closeAll`s on `stop()`.

**Server** (`cd429036`): `PTYRegistry.Open` re-binds an existing id for the
**same operator+device** (refreshes TTL + credit window); a *different* operator
is still refused, or a guessed id would be a session hijack. A new
`Hub.SetDeviceConnectHook` re-sends an OPEN for each live session
(`PTYRelay.ReattachDeviceSessions`), so a reconnected device re-binds the shells
it kept. The device-disconnect hook and the operator-disconnect path no longer
close sessions.

**The browser is the missing third piece.** It must send `pty_open` with the
*same* session id it used before; no browser terminal exists in the server repo
yet (`apps/Vyzorix_web` has no pty client), so that is a contract, not code.
Without it, a reconnect starts a fresh shell and the buffered output is dropped.

**Server build env**: Go 1.27.2 lives at `/workspace/toolchain/go` (persistent);
`apps/api` is its own module. `go build/vet/test ./...` all pass, and the four
drift checks (GraphQL/CUE/swagger/selectors) are clean. `apps/api/go.mod` gets
re-sorted by `go run`; revert it if it shows up in `git status` unrelated to the
change.

## Enriched enroll facts (2026-11-06)

The device collector gathered apiLevel/ABIs/storage/RAM but the enroll request
**dropped them**; the server only saw model/manufacturer/OS. Now both sides carry
the full set: `apiLevel`, `buildId` (`Build.FINGERPRINT`), `securityPatch`
(`Build.VERSION.SECURITY_PATCH`), `supportedAbis`, `ramBytes`, `storageBytes`.

- **`deviceName` is NOT sent by the device.** `Build.MARKETING_NAME` is not public
  API — it does not exist in the SDK's `android.os.Build` (compileSdk 34), so it
  cannot be read. The server's `DeviceName` is the *operator's* name, set at claim
  (`enrollment_service.go` Claim). Do not try to collect it again.
- Server: migration **77** `add_device_facts` adds build_id, api_level,
  supported_abis, ram_bytes, storage_bytes. `security_patch` and `build_id`
  already existed from migration 27 but were never written; `build_id` now is.
  All nullable/zero-defaulted so an older device still enrolls.
- The claim response and `GET /v1/device/:imei` (device detail) return the facts.
  `DeviceDetailResult` lives in `tooling/schema/devices.cue` — edit the CUE and
  run `python3 tooling/codegen/build_schemas.py`, never hand-edit `openapi/schemas.go`.
- **Four in-memory test schemas** declare their own `CREATE TABLE devices` and must
  gain any new column or they fail with "no such column": `device_identity_test.go`,
  `config_service_test.go`, `enrollment_service_test.go`,
  `device_request_auth_test.go`, `device_route_split_test.go`.
- Changing `enrollRequest` changes swagger; regenerate with
  `swag init -g cmd/api/api_main.go --parseDependency --output swag` or the drift gate fails.

## Filesystem access by API level (2026-11-07) — doc/DEVICE_FILESYSTEM_ACCESS.md

Measured, not assumed. **API 33 regresses vs API 28**: the app declares no storage
permission, so on Android 13 shared storage is read-only (`Operation not permitted`)
and `Android/{data,obb}` are denied. The fix is to declare
`READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE` and grant them at runtime;
`requestLegacyExternalStorage` is NOT needed (targetSdk 28 is already on the legacy
path once the permission is held). `MANAGE_EXTERNAL_STORAGE` is not required.

- **`adb shell run-as <pkg>` is NOT a valid probe on API 29+.** It runs in the
  `runas_app` SELinux domain *and a different mount namespace*, so on API 33 it
  reports `/storage/emulated/0` as ABSENT while the app can read and list it. Use
  the instrumented `FsAccessProbeTest` (non-asserting, prints to logcat).
- API 28 probe IS valid via `run-as` (pre-scoped-storage).
- **API 33 AVD**: `system-images;android-33;default;x86_64`, AVD `api33`. The
  default AVD wants a 7.3 GB userdata partition; with `/workspace` tight, delete
  the **regenerable** `~/.android`-style scratch images
  (`avd/x86test.avd/userdata-qemu.img*`) to free ~6 GB, and shrink
  `disk.dataPartition.size` in the AVD's `config.ini`.
- On API 33 the app's supplementary groups gain `ext_data_rw`/`ext_obb_rw`/
  `all_a<uid>`, and `run-as` uses the `runas_app` domain (vs `untrusted_app`).

## Android 13 (API 33) container crash loop — FIXED 2026-11-07

The container never ran on Android 13: proot exited 255 with **zero output**, taking
down the terminal, the supervisor (apps) and every bridge API. It worked on the
Android 9 emulator, which hid it. **Two bugs, both in proot's modern-kernel path.**

**Bug 1 — the SIGSYS case was missing from the ≥4.8 event handler.** proot has two
event loops: `handle_tracee_event` (kernels < 4.8) and
`handle_tracee_event_kernel_4_8` (kernels ≥ 4.8). The `case SIGSYS` existed only in
the first. Android 9's emulator kernel is < 4.8, so it worked there; Android 13 runs
**5.15**, took the 4.8 path, found no case, fell to `default:` ("deliver as-is"), and
the guest died with signal 31. Fixed: added the case to the 4.8 handler too
(`third_party/proot/tracee/event.c`). **Diagnosis technique that worked:** raw
`fprintf(stderr, "MARK: ...")` markers through `main` and the event loop, run from the
**app's own process** — the failing stop was `0x1f7f` (a plain SIGSYS stop) then
`0x1f` (terminated by signal 31).

**Bug 2 — proot's seccomp acceleration corrupts the SIGSYS rewrite.** With bug 1
fixed, the supervisor reached its own code but `mkdir` failed `EROFS` (errno 30).
`PROOT_NO_SECCOMP=1` fixes it. `ProotLauncher.NO_SECCOMP_ENV` is now set for both the
login shell and the supervisor. Costs performance (falls back to `PTRACE_SYSCALL`),
not correctness.

**The methodology trap:** `adb shell run-as` runs in the `runas_app` domain with
**Seccomp: 0** and a *different mount namespace*; the app process is
`untrusted_app_27` with **Seccomp: 2, 1 filter**. So every `run-as` probe looked
healthy while the real app was broken. **Only an instrumented test measures the app's
real behaviour** — that is why this hid for so long.

After both fixes: supervisor prints `ready on /home/apps/supervisor.sock` and stays
up, and `ApiLiveE2ETest` (the full 60-API sweep) passes on API 33.

## Auto-update (OTA) — built and live-verified 2026-11-07

The server had `/v1/check-update`, `/v1/version`, `/v1/apk/*` routes but the device
had **no updater at all**, and the served `version.json` was a **stub** (the SHA-256
of empty input, no APK staged). Both are now real. See `doc/OTA_UPDATE.md`.

- **Device**: `UpdateClient` (check + download + **SHA-256 verify**, refuses a
  mismatch or missing hash), `UpdateChecker` (runs the pass on `onConnected`, own
  thread), `ApkInstaller` seam + `AndroidApkInstaller` (in `:app` — needs an Activity
  and a `FileProvider`; `androidx.core` is an app dep, not the connector's).
- **Install needs the user**: a third-party app cannot install silently. The intent
  is `ACTION_VIEW` + package-archive MIME; `REQUEST_INSTALL_PACKAGES` is declared and
  the APK is exposed via `FileProvider` authority `${applicationId}.fileprovider`
  serving only `files/updates/`. So the mechanism is automatic; the tap is consent.
- **Server `generate_version.sh` had path drift** — wrote `api/v1/version.json` and
  `bin/`, but the server reads `data/version.json` and `bin/` **relative to apps/api**.
  It now writes the paths the server reads. Also: the APK routes registered only GET,
  so HEAD (a client learning the size) got 400 — HEAD is registered now.
- **The release lives in the PUBLIC `gz-_ASSETS` repo**, not `vyzorix-update-server`:
  a private repo's release assets 404 for an unauthenticated device (verified — the
  same trap as the rootfs). `app-v0.1.0` there downloads anonymously (HTTP 200, 64 MB).
- **`make_latest: false` is load-bearing** on any new `gz-_ASSETS` release: the rootfs
  is fetched via `releases/latest/download/...`, so a new "latest" would 404 the
  engine assets. Verified `latest` still resolves to `v1.0.0` after publishing.
- **Live test**: `UpdateLiveE2ETest` (2 tests, **0 skipped**) drives the production
  client against a server on the host (`10.0.2.2:3000` — `adb reverse` does not work
  here) from the Android 9 emulator: 64 MB downloaded and checksum-verified. A device
  already on the latest code does not download.
