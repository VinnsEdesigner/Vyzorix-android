# Project memory — vyzorix-android

## What this is
Headless Android agent: a Debian userspace in `proot`, exposed over a cloud
WebSocket. The server half is `VYZORIX`; this repo is the device
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
- **Regression tests**: `:app`'s `ContainerTerminalE2ETest` (4) and
  `ProotFromAppProcessTest` now pass. `ProotFromAppProcessTest` is flaky when run
  right after `ContainerTerminalE2ETest` (a `libtinfo.so.6 … Operation not
  permitted` race); it passes standalone. Not yet diagnosed.

### Emulator/toolchain notes for this repo
- Toolchain is installed under `/workspace/toolchain` (survives the sandbox wipe
  of everything outside `/workspace`): JDK 21, Android SDK 34 + build-tools 34.0.0
  + NDK 26.3.11579264 + cmake 3.22.1 + emulator + `system-images;android-28;default;x86_64`.
  Source `/workspace/env.sh`; `GRADLE_USER_HOME=/workspace/gradle-home`.
- Build the APK for the emulator with `-ProotfsAbi=x86_64` (the bundled rootfs is
  arm64 otherwise). Fetch it from the public `VinnsEdesigner/gz-_ASSETS` release
  into `debian-container/rootfs/dist/`; the asset repo is private, so use `$GIT` and
  the assets API (`Accept: application/octet-stream`) rather than the raw URL.
- The `emulator` + `adb install` wedge under repeated install/uninstall; `adb emu
  kill` then relaunch (`-wipe-data` if needed). Gradle's device discovery also
  races the adb server — `./gradlew --stop` + restart helps.
- Tests run one class at a time reliably; run the whole suite in one go sparingly.

## Layer 2 is complete (2026-11-05)

The container terminal works and Layer 2's missing pieces are built.

- **`android-bin` is C++, not Go** (design change; the repo has two languages:
  Kotlin/Gradle and C++). `debian-container/android-bin/` — its own Makefile, built for
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
- **The release lives in the PUBLIC `gz-_ASSETS` repo**, not `VYZORIX`:
  a private repo's release assets 404 for an unauthenticated device (verified — the
  same trap as the rootfs). `app-v0.1.0` there downloads anonymously (HTTP 200, 64 MB).
- **`make_latest: false` is load-bearing** on any new `gz-_ASSETS` release: the rootfs
  is fetched via `releases/latest/download/...`, so a new "latest" would 404 the
  engine assets. Verified `latest` still resolves to `v1.0.0` after publishing.
- **Live test**: `UpdateLiveE2ETest` (2 tests, **0 skipped**) drives the production
  client against a server on the host (`10.0.2.2:3000` — `adb reverse` does not work
  here) from the Android 9 emulator: 64 MB downloaded and checksum-verified. A device
  already on the latest code does not download.

## Download progress (OTA) — 2026-11-07

`POST /v1/download-progress` existed but was **log-only** — the server accepted
progress and could not answer for it. Now both halves are wired:

- Device `UpdateClient` reports as it copies, **throttled to whole-percent changes**
  (a 64 MB download in 8 KB reads would be thousands of requests). A failed report
  is swallowed — progress must never fail the download. Device id is a **provider**
  (unknown until enrollment finishes).
- Server `ProgressStore`: in-memory, keyed by device, **2-minute TTL**; a device that
  stops reporting reads as `downloading:false`, not a frozen percent. Percent is
  derived from the byte counts. Read via `GET /v1/download-progress/:deviceId`
  (registered with and without `/api`).
- **`scripts/deadcode_gate.py` scanned only the first 4000 chars of a function
  body** — a param used past that was a false positive. Fixed to brace-match the
  whole body; re-verified it still catches a genuinely unused param.
- Live: device reports 0..100% for the 64 MB APK; server returns the record with the
  right filename. `UpdateLiveE2ETest` is 3/3, 0 skipped.

## proot is now the Termux fork — 2026-11-07

**The vendored proot is `termux/proot` v5.1.107.102, not upstream `proot-me`
v5.5.0.** Upstream could not run a real workload on Android 13: its SIGSYS handling
was hand-patched here and still broke three things an operator would hit. Termux's
fork is the same code base built for exactly this environment (Termux runs under the
same app seccomp filter). Detail + the three upstream defects:
`2026-11-07-termux-proot.md`, `third_party/README.md`.

- **`PROOT_NO_SECCOMP=1` must NOT be set.** It was required by upstream; with Termux
  it *breaks* `rename(2)` (ENOSYS), because Termux's seccomp path is the mechanism by
  which it handles the trap. `ProotLauncher` now sets only `PROOT_TMP_DIR`; the
  constant is gone.
- **The NDK port** is `third_party/proot/CMakeLists.txt` + `cmake/gen-loader-info.cmake`.
  Three traps: ninja passes `-D` values **with literal quotes** (a shell strips them,
  ninja does not — the pokedata script must strip them itself); `loader.unstripped`
  must be a declared OUTPUT and the symbol read an ordered command on the same rule
  (a second rule sharing the file is a race ninja wins by deleting it); and the
  upstream awk uses `strtonum` (gawk-only) so the offset is computed in CMake.
- **The rootfs must ship `/etc/hosts`.** glibc's `getaddrinfo` segfaults without it
  (it dereferences the file unconditionally). Fixed in `scripts/build-rootfs.sh`, and
  the bundled tarballs + `SHA256SUMS` were patched to match. `/etc/services` and
  `/etc/protocols` too — libcurl reads them.
- **`OperatorCommandE2ETest`** runs one arbitrary command through the production
  terminal path (`-e command '<cmd>'`), chunking the transcript to logcat. It is how
  the container was driven; a real `apt-get install build-essential … && make` runs in
  it.
- **Verified on API 33, from the app process:** `cd`, `rename`, `poll`, `getaddrinfo`,
  `curl` (200), `git clone` + `ls-remote` over HTTPS, `apt-get update`/`install`, and
  the **Vfd engine built in the container** (`media-ingest-c/build/vyzor`, runs on the
  host). `node`/`npm` install but **npm crashes at startup under proot** (a
  `readFileSync` size mismatch) — so the React console cannot be built in-container.

## Node/npm in the container — 2026-11-08

**npm works in the container now** (it did not before). Two defects, both fixed by
one prelude loaded via `NODE_OPTIONS=--import`; detail: `doc/NODE_IN_CONTAINER.md`,
`2026-11-08-node-in-container.md`.

- **THE BIG ONE: the emulator CPU mis-emulates `cvttsd2si(NaN)`.** No KVM here →
  QEMU TCG; it returns `INT32_MAX` where x86 defines `INT32_MIN` (reproduced in a
  **static C program with no proot**). V8's `ToInt32` maps the indefinite result to
  0; the mapping is missed, so **`ToInt32(NaN)` is `-1`** and *every bitwise op* is
  wrong: `undefined|0` → -1, `undefined&8` → 8, `undefined>>>0` → 4294967295.
  `Buffer.prototype.toString(enc)` does `start |= 0` on `undefined` → -1 → throws, and
  node reads every module with that call, so **nothing could load from disk**. Not a
  proot bug, not fixable by swapping proot, and not present on a real device.
- **A proot rename can report ENOENT after it succeeded** (dest already exists) —
  breaks npm's cacache (~25% of puts). The prelude treats "ENOENT but dest exists" as
  success.
- **`tsc` still fails** on `undefined & 8` in TypeScript's own internals — a bitwise
  op, unpatchable from JS. Host tsc on the same commit is clean. Emulator-only.
- Works in-container, from the app process: node (CJS+ESM), `npm install`, `npm run
  build` (vite), `pnpm install`, `vite build`, `vitest run` (assertions pass), rollup,
  esbuild, prettier, eslint. Only `tsc` fails.
- `pnpm` needs `--config.dangerouslyAllowAllBuilds=true` (esbuild postinstall), and
  Debian's `npm install -g` half-completes ("Exit handler never called", no bin
  symlink) — run pnpm via `node /usr/local/lib/node_modules/pnpm/bin/pnpm.mjs`.
- **Disk is the recurring failure.** The container's installs grow the AVD userdata
  (→6.8 GB); `/workspace` hit 96% and killed the emulator twice. Free
  `gradle-home/caches/build-cache-1` + `avd/api33.avd/cache.img.qcow2` and restart.

## Go toolchain + server in the container — 2026-11-08

**The Go server builds and serves inside the container, no crash.** Verified from
the app's own process: `go build` → 70 MB binary; the binary runs and answers
`/healthz`, `/health`, `/v1/version`, `/v1/changelog`, `/v1/check-update` all 200,
process stays alive, **0 panics/fatals**; `go vet` and `go test` both pass. Detail:
`2026-11-08-go-in-container.md`.

- **Debian's Go is 1.19; the server needs 1.27.2.** Install the official tarball:
  `curl -fsSL https://go.dev/dl/go1.27.2.linux-amd64.tar.gz` then extract to
  `/usr/local` (go.dev is reachable from the container). `go version` → 1.27.2.
- **Build needs `GOMAXPROCS=1` and `-p 1`.** The emulator has only 2 GB RAM; a
  default-parallelism build was **OOM-killed** (log ended with no message). With
  `-p 1` it completes.
- **The link step needs headroom.** `gocache` (~700 MB) + `gopath` (~950 MB) filled
  the container's 5.9 GB root and the link failed with "no space left on device".
  Free the Go tarball and the Vfd `node_modules` first.
- **Stage the server source via `files/apps` (`/home/apps` in the container)** —
  `/data/local/tmp` is NOT bound (`/mnt` was not), and `run-as ... cat >` into
  `files/apps/` works. `tar` with `--exclude=apps/api/bin` (124 MB of APKs) keeps it
  ~4 MB.
- **A server started as a background child dies when the PTY session closes**
  (proot `--kill-on-exit`). Run it and curl it **in the same `am instrument` call**,
  or keep the session open. That is why the first run showed `DEAD` on the next call.

## Deploy — device side (Build 1 landed 2026-11-09)
- **Contract**: `VYZORIX/DOCUMENTATION/DEPLOY_PIPELINE.md`.
  The device half (§13.3/§13.4) was entirely unbuilt — the contract's claim that
  `SecretEnvelope.kt` "is already written" is stale; there is no `secret/` package.
- **Build 1 (this commit) = config foundation**, in `config/`:
  `AppConfig`/`ArtifactConfig`, `AppConfigReader`, `ConfigSecretScan`,
  `ConfigValidator`; `ConfigVerifier` now reads `apps` and runs the scan + rules.
  Nothing fetches or applies yet (that is `ArtifactFetcher`/`DeployApplier`).
- **The libv gotcha that cost a cycle**: an app's NAME is the node's *value*
  (`vvv app vfd` → `tokens[1]`), NOT a `name` child; the same for `artifact`
  (`vvvv artifact engine`). Only fields are children. Verified with a throwaway
  `VNode.toString()` dump.
- **`VNode.find("device.apps")` works** (one level per dot segment) and returns a
  list; `VNode.children` and `VNode.getKey()` exist. Apps live at
  `device.apps.app`. Do NOT flatten with `find("device.apps.app.port")` — that
  crosses every app and mixes their ports (contract §5.1).
- **The no-secret check is structural, not a line scan** (§5.2). The old
  `ConfigVerifier.assertNoSecrets` refused the word "secret" anywhere, which
  refuses the schema's own `secret NAME` node. The authoritative reference is the
  server's `apps/api/internal/infrastructure/configscan/vyzorix_config_secret_scan.go`
  — the Kotlin `ConfigSecretScan` is a port and the two must agree.
- **Toolchain/emulator**: `scripts/install-toolchain.sh` → `/workspace/toolchain`
  (JDK21 + SDK 6.1G + AVD x86test + cross-compilers), then
  `source /workspace/toolchain/env.sh` and `echo sdk.dir=$ANDROID_HOME > local.properties`.
  `./gradlew gate` is green in ~1m20s (nativeGate compiles the whole C++ tree, so
  it is the slow part). Run long gradle jobs detached: `setsid bash -c './gradlew
  gate < /dev/null > log 2>&1' &` — a plain `&` dies with "Bad file descriptor".
- **ktlint**: `./gradlew :cloud-connector:ktlintFormat` fixes most of it, but it
  does NOT fix chain-method-continuation / argument-list-wrapping / max-line-length
  (100) in one pass — re-run `ktlintCheck` and hand-fix the rest.

## Deploy — device side, Builds 2 & 3 (2026-11-09)
- **Build 2** (`cloud-connector/.../deploy/` + `secret/`): `ArtifactFetcher`
  (resumable fetch, whole-file digest), `DeployApplier` (ping supervisor, fetch +
  stage all, resolve secrets, purge, per-artifact rename install, write secrets +
  PWA, write registry, stop removed), `AppRegistryWriter` (the supervisor's
  `apps.json` shape — NOT the config), `SecretKey` (Keystore RSA-3072,
  PURPOSE_DECRYPT, **OAEP-SHA1 only** — the Keystore rejects MGF1-SHA256),
  `SecretStore`/`HttpSecretTransport`, and delivery wired in `CloudConnection`
  (enroll sends `secretKey`; a verified config's apps are applied off-thread and
  the outcome reported as a `device_ready` frame).
- **Build 3**: `PwaManifest` — writes `site.webmanifest` into a webroot's `www/`
  only when `pwa = true`; `ConfigValidator` refuses an invalid `display`.
- **Review caught three real bugs** (fixed): artifacts were installed *before*
  secrets were written (a bad secret left new artifacts, no `.env`); the transport
  ignored the server's `envelope` field; a thrown apply would kill its thread with
  no report. `SecretStore` is now `resolve()` + `writeEnv()` so validation happens
  before any live-tree mutation.
- **Emulator**: `scripts/run-emulator.sh` boots in ~1-3 min (no KVM, `-accel off`).
  Instrumented tests must use camelCase method names — **spaced backtick names
  fail D8** ("Space characters in SimpleName … not allowed prior to DEX 040").
- **The secret roundtrip is proven on-device**: `SecretEnvelopeRoundtripTest`
  (instrumented) — a real Keystore key, the server's own Go `crypto.WrapSecret`
  ciphertext (served by a tiny host helper over `adb reverse tcp:3000`), decrypts
  to the expected value; a foreign ciphertext does not open. 2 tests, 0 skipped.
- **Go for the server**: `go1.27.3` tarball extracts to `/usr/local` only with
  sudo; extract to `/tmp` instead and run `/tmp/goextract/go/bin/go`. Building the
  server needs `GOTOOLCHAIN=local`.
- Pushed: `774497e` (Build 1) → `8d15411`/`2bae416`/`59294bb` (Build 2) →
  `9d71dde` (Build 3).

## Deploy — device side, Builds 4-6 (2026-11-09)
- **Build 4**: `ConfigStore` keeps current + last-good (`keep_last = 2`, FR-21).
  `apply` promotes the replaced doc to last-good; a current doc that no longer
  verifies falls back to last-good; `rollback()` is called after a refused apply.
  `ConfigStorage` grew `readLastGood`/`writeLastGood` (FileConfigStorage persists
  `vyzorix.v` + `vyzorix.last-good.v`).
- **Build 5**: exposure wiring (US-011). `SupervisorControl.expose/unexpose`;
  `DeployApplier` exposes `lan` on `0.0.0.0` with a token, unexposes `loopback`.
  **The token must NOT live under `files/apps`** — that dir is bound to `/home/apps`
  and proot is root inside, so every app could read it. `ExposeTokenSource`
  persists it app-privately (`filesDir/expose-tokens`). The `lan` token delivery is
  unspecified by the contract — this is an assumption to confirm.
- **Build 6**: `AppCmdCheck` re-checks `cmd` on-device after install (§8 rules 3-4):
  symlink-resolved path inside the app dir, and exists+executable. Runs after
  install, before the registry.
- **Gate/test gotchas**: unit-test fixtures that pass the `cmd` check must ship an
  **executable** `run.sh` (tar mode 0755) AND the `cmd` must point where the
  artifact actually unpacks (`<app>/<artifact>/run.sh`), not the app root. ktlint
  `ktlintFormat` fixes most style but not chain-method-continuation /
  argument-list-wrapping / max-line-length in one pass. `File.createSymbolicLink`
  is not resolvable in unit tests — use `java.nio.file.Files.createSymbolicLink`.
- Pushed: `ae3baf0` (B4) → `c9df268` (B5) → `0ab86eb` (B6). Device-side deploy is
  now feature-complete per §13.3/§13.4.

## Live E2E (2026-11-09) — 6 real bugs, all fixed
Ran the full loop: provision -> boot server -> build+install APK -> enroll ->
claim -> CLI login -> deploy -> app serves HTTP 200 from the host. Every bug
below blocked it and none was covered by a unit test.
- **Server: every login 500'd.** `RefreshTokenRepository.Create` passed
  `ReplacedByID` (Go zero = "") into a self-referencing FK -> "FOREIGN KEY
  constraint failed". Fixed with `nullString`. (server `50069afe`)
- **Server: every config/command push failed the device signature.** `json.Marshal`
  HTML-escapes `<`/`>`/`&` to `\u003e`, but the signature covers `args` verbatim, so
  sent bytes != signed bytes. A `backoff 1s>30s` node was enough. Fixed with
  `SetEscapeHTML(false)` + writing the exact signed bytes. (server `50069afe`)
- **Device: no compressed frame could be read.** The server gzips frames >1 KiB into
  `{type,data,compressed:true}`; the dispatcher only read `args`. Added
  `FrameCompression`. (device `ea9f852`)
- **proot aborted the tracer** on a mis-timed seccomp event (Android 9 delivers at
  sysexit; `event.c` asserted IS_IN_SYSENTER -> abort -> supervisor SIGABRT on every
  fork). (device `ea9f852`)
- **proot's seccomp acceleration segfaults the guest on API 28.** `PROOT_NO_SECCOMP=1`
  fixes it; gated to API <= 28 (on API 33 it breaks `rename(2)`). `ProotLauncher.prootEnv`.
- **Layer 3: a stop did not stick.** The supervisor's own `apps.json` write triggered
  its inotify reload + `StartAutostart`, restarting the app just stopped. Fixed with
  `Manager::RegistryOnDiskDiffers`. Also `AdoptRunning` reclaims live processes after a
  restart (they were otherwise orphaned and unstoppable). (device `a6ad9e0`,`002c24a`)
- **`Layer3Installer` marker was ABI-only**, so a rebuilt supervisor never reached a
  device that already had one — cost a cycle. Now `abi:assetLength`. (`8147bd2`)
- **Also:** python3 is now in CORE_PACKAGES (first boot), not just extended.
- **Test-harness gotchas:** the APK must be built with `-PserverPublicKey` (PEM with
  literal `\n`), `-PcloudBaseUrl=http://10.0.2.2:3000` (10.0.2.2 is cleartext-allowed),
  and for a local artifact host `-ProotfsBaseUrl=https://10.0.2.2:8443` with a CA
  installed into `/system/etc/security/cacerts` (needs `emulator -writable-system` +
  `adb remount`). `control_test` fails if anything holds port 3000 (it binds it).

## Release script (2026-11-10, pushed 5d946fb)
`scripts/release-apk.sh` — one command: toolchain check -> ensure arm64 inputs ->
gate -> build APK -> sign -> publish a GitHub release (APK + SHA256SUMS).
- Flags: `--debug` (default release), `--tag`, `--version-code/--version-name`,
  `--server-key[-file]`, `--cloud-url`, `--repo`, `--dry-run`, `--skip-tests`.
- **Release-only build trap**: `app/build.gradle.kts` declared the staged-asset
  dependency only for `merge*Assets`, so `lintVitalRelease` (runs on release, not
  debug) failed with an undeclared-dependency error. Fixed by also matching Lint/lint
  task names. If you add a task that reads `app/build/generated/{rootfs,layer3}Assets`,
  add it to that matcher.
- An **unsigned release** APK is `app-release-unsigned.apk` (not `app-release.apk`);
  the script takes whichever exists.
- **Publishing needs a token that can see the repo**: `GITHUB_TOKEN` here is
  `quavokheed` and cannot resolve `VinnsEdesigner/Vyzorix-android` (private). `$GIT`
  works — pass it as `GH_TOKEN="$GIT"`.
- `--server-key` is a PEM; the script converts newlines to literal `\n` for the
  Gradle property (the value goes into a Java string literal).

## "engine" -> "container" rename (2026-11-11)
The device-side concept once called "the engine" is now **"the container"** (proot is a
userspace container; "engine" was the vague word). Mechanical rename across both repos:
- Kotlin package `com.vyzorix.engine` -> `com.vyzorix.container`; `EngineConfig` ->
  `ContainerConfig` (file renamed); `isEngineReady`/`onEngineLost` ->
  `isContainerReady`/`onContainerLost`; `isEngineZonePath`/`ENGINE_ZONE_DIRS` ->
  `isSandboxZonePath`/`SANDBOX_ZONE_DIRS`; `EngineTerminalE2ETest` ->
  `ContainerTerminalE2ETest` (file renamed); `ENGINE_READY_TIMEOUT_MS` ->
  `CONTAINER_READY_TIMEOUT_MS`. Prose "engine zone" -> "sandbox zone".
- Dir `debian-engine/` -> `debian-container/` (scripts, README, .clang-tidy,
  .gitignore, AGENTS.md, docs all updated). `ProotLauncher` kept its name.
- Server cross-refs updated: `apps/api/internal/domain/permission/scoped.go` comment,
  `documents/ANDROID_SERVICE_{ARCHITECTURE,BOUNDARY_CONTRACT}.md`,
  `DOCUMENTATION/DEPLOY_PIPELINE.md`. The `fs` zone name `engine` -> `container`
  in the boundary contract (prose only; the server never implemented it).
- **Deliberately NOT renamed**: the deploy-domain *app artifact* named `engine`
  (a fixture name, e.g. `[[app.artifact]] name = "engine"`, `/home/apps/<app>/engine/`)
  in cloud-connector tests and server deploy tests/docs; the gin `s.engine` /
  "permission engine"; "audio engine". Do not "fix" those.
- Verify recipe that works here: `./gradlew ... -x :app:buildLayer3 -x :app:stageLayer3
  -x :app:stageRootfs` (the glibc cross-compiler is absent this session, so those native
  tasks fail for an unrelated reason). `:app:buildLayer3` also needs ktlintFormat run
  first after a rename (longer identifiers can push a line past 100 chars).

## APK release (2026-11-10) — app-v0.1.3 published
Published to `VinnsEdesigner/gz-_ASSETS` (public, so a device fetches it
anonymously): tag `app-v0.1.3`, asset `vyzorix-app-v0.1.3-release-arm64.apk`
(70 MB, sha256 `923bba79c61b65431ee267915f426d0e31e4d8299bfa2ffadb880873401224d1`).
Built by `scripts/release-apk.sh` with the pinned server key
`/workspace/.vyzorix-secrets/server.pub.pem` and `--cloud-url https://vyzorix.fly.dev`.

- **Keystore**: `/workspace/.vyzorix-secrets/vyzorix-release.jks` (PKCS12, alias
  `vyzorix`, RSA-4096, 10000d). Passwords in `/workspace/.vyzorix-secrets/keystore.env`.
  Cert SHA-256 `8851e9ff…d0779`. **Back this up** — losing it means no future update can
  install over an existing install.
- **`buildLayer3` needs a glibc cross-compiler.** Not present by default; install with
  `sudo apt-get install -y g++-aarch64-linux-gnu`. Without it the task is skipped and
  the APK is bridge-only (no supervisor).
- **THE `latest` TRAP (hit live, now fixed in the script).** The rootfs is fetched by
  devices from `releases/latest/download/debian-core-*.tar.gz`, and the assets repo is
  shared with the rootfs releases. A plain `gh release create` moves GitHub's "latest"
  pointer, so publishing the APK made the rootfs 404 — breaking every fresh install.
  `app-v0.2.0` did exactly this. Both publish paths now pass `make_latest=false`.
  Note: the REST PATCH of `make_latest` silently does nothing; `gh release edit <tag>
  --latest` works, and the `releases/latest` redirect is cached for ~1 minute.
- Version naming: the user wanted `v0.1.3` (app-v0.2.0 was deleted).

## Play Protect block, and the `sideload` flavor (2026-11-10)
On a Nokia C22 (API 33, Go edition), Play Protect refused the install with
**"this app is blocked from installing, it's malicious and could cause a financial
fraud"** — a HARD, non-overridable verdict (no "install anyway"), so turning the
Play Protect toggle off does not help: the verdict is fetched from Google's cloud.
`adb install` bypasses Play Protect entirely, but tapping an APK in a file manager
does not.

The verdict is the SMS-fraud/banking-trojan signature. The permissions that make it
up, all of which the app genuinely used: `SEND_SMS`+`RECEIVE_SMS` (fraud marker),
`READ_LOGS` (credential scraping), `REQUEST_INSTALL_PACKAGES` (dropper),
accessibility service (overlay), `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`,
`CALL_PHONE`.

**Fix**: a `distribution` flavor dimension in `app/build.gradle.kts`. `full`
(default, unchanged) keeps everything; `sideload` removes those seven via
`app/src/sideload/AndroidManifest.xml` (`tools:node="remove"`). Removed APIs fail
closed, not fatally — `ApiRouter.dispatch` catches `Throwable`, handlers catch
`SecurityException`. `scripts/release-apk.sh --flavor sideload` builds it; the APK
name carries the flavor.

- **`isDefault = true` on the `full` flavor is load-bearing**: it keeps
  `assembleRelease`/`assembleDebug`/`gate`/instrumented tests resolving without a
  flavor prefix, so adding flavors did not break CI or the gate.
- Build it by hand with: `./gradlew :app:assembleSideloadRelease -ProotfsAbi=arm64
  -PcloudBaseUrl=... -PserverPublicKey="<escaped PEM>"`. The PEM must have literal
  `\n` (the release script does this); a raw multi-line PEM fails
  `compileSideloadReleaseJavaWithJavac` with "unclosed string literal".
- Published `app-v0.1.4` (sha256 `95b905b8…`), baked into the server image, live at
  `https://vyzorix.fly.dev/v1/apk/vyzorix-app-v0.1.4-sideload-release-arm64.apk`.
- **Do not pass commit messages containing backticks through a double-quoted
  `git commit -m`** — the shell runs them as command substitution and mangles the
  message. Use `git commit -F -` with a quoted heredoc.

## API 33 emulator E2E (2026-11-10) — pairing code verified live
Ran build -> install -> accessibility -> enroll against `https://vyzorix.fly.dev` on
an Android 13 emulator. Pairing code shown in the notification. Key facts:
- The API 33 image was **not** installed by default (`x86test` is API 28); install
  `system-images;android-33;default;x86_64` and create an AVD. No KVM here -> `-accel off`.
- **AVD userdata is allocated eagerly**; the emulator refused to boot needing ~7.4 GB.
  Editing `disk.dataPartition.size` in config.ini is overwritten — pass
  `-partition-size 2048` (and free disk) instead.
- **`./gradlew clean` fixes a Kotlin front-end `EOFException` ICE** after the recent
  package rename (stale incremental cache). Do this before debugging further.
- The FGS is **not exported**; `am start-foreground-service` fails from the shell.
  `adb root` first, then it works. Action `com.vyzorix.service.action.START`.
- Enabling accessibility (settings put secure enabled_accessibility_services +
  accessibility_enabled 1) posts the "Start Vyzorix" notification and disables the
  launcher alias. The pairing code appears after the FGS starts.
- Open question: `dropped a frame: command frame signature invalid` every ~60 s —
  the WS push frames are refused (device clock or pinned-key mismatch?). Config
  polling still works, so pairing is unaffected. Full detail:
  `2026-11-10-api33-emulator-e2e.md`.

## Claim flow + the telemetry frame-drop bug (2026-11-10, later)
- **Claim is CSRF-protected**: `GET /v1/auth/csrf-token` -> `POST /v1/auth/login`
  (`X-CSRF-Token`) -> `POST /v1/device/claim` (cookie + `X-Organization-ID` +
  `X-CSRF-Token`, body `{"pairingCode":"XXXX-XXXX"}`). Operator creds in
  `/workspace/.vyzorix-secrets/provisioning.yaml`.
- **Pairing code TTL is 10 min and the device never refreshes it** (re-enroll 409s once
  claimed). To claim, restart the FGS for a fresh code, then claim immediately.
- **`vyzorix.fly.dev` is >1 machine with in-memory sessions** — a login then a claim
  that land on different machines gives `AUTH_TOKEN_INVALID`. Retry the pair.
- **`dropped a frame: command frame signature invalid` (every ~60s) was the telemetry
  echo.** The server `BroadcastTelemetry` sends every client (incl. the sender) an
  unsigned `{type:"broadcast"}` frame each tick; the device treated it as a command.
  Fixed device-side: `CommandDispatcher` returns `Handled.Ignored` for a frame with an
  empty `command` (a real command always has one), logged at debug. Server is correct
  (`TestHubBroadcastTelemetry`). Verified: 0 drops on the emulator.
- **"Not running" on an already-configured restart**: `applyConfig` (which posts the
  running notification) only runs for a *new* config, so a restart showed "Not running".
  Fixed with `CloudConnection.reconcileNotificationFromStoredConfig()`.
- **Publishing**: `scripts/release-apk.sh` needs `GH_TOKEN="$GIT"` (the ambient
  `GITHUB_TOKEN` is `quavokheed` and cannot see `VinnsEdesigner/gz-_ASSETS`), the
  keystore env, and **`g++-aarch64-linux-gnu`** installed (its internal gate runs
  `:app:buildLayer3`). Published `app-v0.1.5`; `--latest=false` kept "latest" on
  `v0.1.2` so the rootfs still resolves. Source commit `8a719c5` is local-only.

## Server logs, DB, and the expired pairing code (2026-11-10, later still)
- **`flyctl` install**: official release tarball, `export FLY_API_TOKEN="$FLY_IO_ORG_TOKEN"`,
  then `flyctl logs -a vyzorix`. **Turso** via the `/v2/pipeline` HTTP API with
  `$TURSO_Vyzorix_main_TOKEN` (null cells have no `value` key; column is `api_level`).
- **Pairing codes expire in 10 min and the device never refreshes them** (it enrolls
  once; `PairingCode.isExpired` is unused). So a claim more than 10 min after the device
  booted 404s with "pairing code not found". Fix pending: re-enroll when the code expires.
- **Minting a fresh code for an already-enrolled-but-unclaimed device**: re-POST
  `/v1/device/enroll` with the *same* uuid + its stored publicKey/secretKey (idempotent
  when unclaimed), then claim immediately.
- Claimed the real Nokia C22 (`c48108aa…`): server `device claimed`, DB org+operator set,
  `online=1`, and its config polls stopped (config received). No server errors.
- `device_ready` is **not logged** by the server, so that step is unverifiable from logs.
- **Pairing-code refresh FIXED (84e87ca).** The device enrolled once and never refreshed a
  lapsed code. `CloudConnection.fetchConfigOnce` now calls `refreshPairingCodeIfExpired()`
  (re-enroll when `PairingCode.needsRefresh`, i.e. 30s before expiry) on the config-poll
  cadence, and clears the code once a config arrives. `Deps.clock` is injectable for tests.
- **Sending a command (operator path)**: `POST /v1/device/{deviceId}/command` with the
  session cookie + `X-CSRF-Token` + `X-Organization-ID` and body
  `{"deviceId":"<uuid>","command":"SHELL_EXEC","args":{"cmd":"id"}}` -> 202 `status:sent`.
  A **GET** (e.g. `/v1/command/{dispatchId}/status`) DOES need `X-Vyzorix-*` session-HMAC
  headers ("Missing required signature headers"); a cookie POST does not. Verify the
  outcome in the `commands` table (Turso): `status=completed`, `result=<output>`.
- The device's `SHELL_EXEC` result comes back over the WS as a `command_result` frame; the
  server records it and the command row moves to `completed`.

## SMS read API, permission class fix, migration, icon, OTA 0.1.6 (2026-11-10)
- **`sms_read` added** (device `5135339`): reads the telephony inbox via
  `Telephony.Sms.Inbox.CONTENT_URI` with `READ_SMS`. Bounded page (default 50, cap 200,
  newest first); returns sender/date/read/type/body + `totalAvailable`; `readable:false`
  when READ_SMS is absent. `sms_received` stays count-only by design.
- **The "APIs that don't return results" root cause**: the manifest declares CAMERA,
  ACCESS_FINE_LOCATION, CALL_PHONE, READ_PHONE_STATE, SEND_SMS, RECEIVE_SMS, READ_SMS,
  but `LaunchActivity` requested only 4 permissions by hand, so `camera`, `location`,
  `phone_state_detailed`, `sms_capability`, `sms_received`, `sms_read` returned FORBIDDEN
  forever. Fixed: `LaunchActivity` derives the set from
  `PermissionRegistry.dialogGrantablePermissions(sdkInt)` (new) + the two app-level perms
  (shared storage, BLUETOOTH_CONNECT). `call_capability` stays ungated (reports, no dial).
- **Notification icon**: `android-core/src/main/res/drawable/vyzorix_notification_icon.xml`
  (terminal glyph) replaces `stat_sys_download_done`. android-core now HAS a res/ dir;
  `DeviceNotification` imports `com.vyzorix.R`.
- **Accessibility description** expanded in `app/src/main/res/values/strings.xml`.
- **Server migration 83** (`8ed4cbab`): `notification_audit_log` lacked `device_id`,
  `success`, `error_msg` that `notification.Repository.LogEntry` inserts, so every audit
  write failed. Applied on deploy ("Applied migration 83"). DB now has all 9 columns.
- **OTA**: bumped the catalog to 0.1.6/code 6, built+**signed** the arm64 release APK,
  staged with `tooling/scripts/generate_version.sh` into `apps/api/bin/` +
  `apps/api/data/version.json` (APKs are **gitignored** but NOT dockerignored, so they
  reach the image via the build context — that is the intended "publish = redeploy"
  flow). Deployed; `/v1/check-update` returns 0.1.6 and the APK serves (HEAD 200).
- **Device is offline** (Nokia C22, last seen 18:11), so the live `sms_read` query is
  pending a reconnect. Note: app 0.1.5 is the *full* build (not the sideload flavor), so
  OTA works; the agent checks `/v1/check-update` on WS connect and hands the APK to the
  package installer (the last tap is the user's — a third-party app cannot silently install).

## Notification states, burgundy theme, icon-hide fix, OTA 0.1.7 (2026-11-11)
- **Launcher icon never hiding** — the hidden state was asserted once, from
  `VyzorixAccessibilityService.onServiceConnected`. It is a package property that any
  reinstall/update resets, and the service is not re-bound on every process start. Fixed
  with `LauncherIcon.reconcile(context)` called from `VyzorixApplication.onCreate` AND the
  service's onCreate/onServiceConnected. `isArmed` reads `Settings.Secure.
  ENABLED_ACCESSIBILITY_SERVICES`. Verified on API 33 emulator (icon hides on enable,
  restores on disable). Device commit `f17e9ef`.
- **`DeviceNotification` states + burgundy**: `setColor(0x800020)` on every state tints the
  icon/app-name/emphasis. States: needsStart, starting, waiting, running(networkUp),
  downloadingUpdate(version,percent), updateDownloaded, applyingUpdate. `NetworkMonitor`
  (validated-internet NetworkCallback) re-posts running on connectivity change;
  `UpdateMarker` file carries "installed" across the update's process restart so the new
  process shows "reconnecting".
- **`UpdateChecker`** gained `onProgress`/`onDownloaded`/`onInstalling`; `UpdateClient.download`
  takes an `onProgress` and reports locally as well as to the server.
- **deadcode gate gotcha**: platform-invoked overrides (`NetworkCallback.onAvailable/onLost/
  onCapabilitiesChanged`) read as unused; allowlist them in `config/deadcode-allowlist.txt`.
  An unused private/public decl must be removed or allowlisted — the gate fails otherwise.
- **OTA 0.1.7**: built+signed arm64 release, staged into `apps/api/bin/` + version.json,
  deployed (server `4fe0c04a`). The Nokia C22 downloaded it to 100% (server logged
  `download progress` up to percent 100) but the **install is the user's tap** — until it
  is tapped the device still runs 0.1.5, so `sms_read`/READ_SMS is absent and the inbox
  query returns 0 rows. Publishing an APK is a redeploy; it is gitignored, not dockerignored.

## Querying SMS on a real device — two blockers (2026-11-11)
The device is on **0.1.8** (server log `update check version_code: 8`) and has
`sms_read` + READ_SMS, but neither access path works from the outside:
1. **The device shell cannot read the inbox.** `SHELL_EXEC` runs as the app uid
   (`u0_a204`, `untrusted_app_27`) which now HOLDS READ_SMS, but the `content` tool is
   `/system/bin/content`, a script that execs `app_process` — and **SELinux blocks
   untrusted_app from executing `app_process`/`dalvikvm`/`art_exec`** (the ART tools in
   `/apex/com.android.art/bin` are "inaccessible or not found" to it). So `content
   query --uri content://sms/inbox` prints nothing and exits 0 — it looks empty but the
   tool never ran. `pm`/`dumpsys` are likewise absent from the untrusted_app path.
2. **The `sms_read` bridge API needs a session token, and the only mint site is the
   operator terminal**, which is `PTY_SESSION_OPEN` — RiskTierCritical, RequiresConfirmation,
   and gated on an **MFA-verified session** (`GET /v1/dashboard/device/:imei/terminal`
   returns HTTP 449 `SECURITY_RISK_UNCONFIRMED: requires an MFA-verified session`). The
   provisioning operator has `mfa_enabled: false`, so the terminal cannot be opened from
   the API. Session tokens are minted only at `ServicePtySessionPort` /
   `VyzorixForegroundService` for that terminal path — there is no non-MFA mint site.
- **Consequence**: SMS can only be read by the app in-process (what `sms_read` does);
  there is no shell or API route to it without an MFA-verified operator terminal.
- Route prefix gotcha: the terminal is `/v1/dashboard/device/:imei/terminal` (NOT
  `/v1/device/...`), and the WS needs the `X-Organization-ID` header.

## Device-admin + control APIs, verified live (2026-11-11)
- **The app can be device owner AND admin.** Added `VyzorixDeviceAdminReceiver` +
  `res/xml/device_admin.xml` in `android-core` (declared in core's manifest). Emulator:
  `adb shell dpm set-device-owner com.vyzorix.agent/com.vyzorix.service.VyzorixDeviceAdminReceiver`
  -> "Success". On a real device without adb the only routes are QR/NFC provisioning on
  a factory-reset device.
- **New APIs**: `device_admin_request` (opens the activation screen),
  `call_control` (place a call; gates), `usb_control` (enable USB debugging / clear
  DISALLOW_USB_FILE_TRANSFER). Enriched `device_owner` (admin components),
  `device_owner_capabilities` (full policy dump), `developer_options` (enable +
  canWriteSetting), `accessibility` (enabled+installed).
- **`device_owner_capabilities`** verified live: `canLockNow:true`, full password/lock
  policy keys, `storageEncryptionStatus:5`.
- **Two platform limits, both verified live:**
  1. **Owner components are UNREADABLE.** `getDeviceOwnerComponentOnAnyUser` is
     `@SystemApi`; `getDeclaredMethod`+`invoke` throws `SecurityException: Calling
     identity is not authorized`. The API reports `deviceOwnerComponent:""` and logs the
     cause. Do not assert it non-empty.
  2. **`setGlobalSetting` has an allowlist.** `development_settings_enabled` is NOT on
     it — the owner call returns without throwing and changes nothing. `DeviceSettingsWriter`
     now VERIFIES the value after writing and falls back to `Settings.Global.putString`
     when `WRITE_SECURE_SETTINGS` is held. Without that grant, enabling dev options fails
     (correctly reported as `enabled:false`).
- **`usb_control` enable works** (adb_enabled is allowlisted): `usbDebuggingEnabledNow:true`.
- **Test gotcha**: `ApiRouter()` with no `PermissionRequester` refuses every gated API
  (`call_control` -> "runtime permission not granted"). The instrumented test must pass
  `ApiRouter(PermissionRequester(context))`.
- **Instrumented test naming**: minSdk 26 -> DEX < 040 rejects backticked method names
  with spaces ("Space characters in SimpleName ... not allowed prior to DEX version 040").
  Use snake_case names in `tests/app/instrumented`.
- Live test: `./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64
  -Pandroid.testInstrumentationRunnerArguments.class=com.vyzorix.agent.AdminApisLiveTest`.

## Bluetooth / WiFi / wallpaper APIs (2026-11-11)
- **`bluetooth_control`** (service-registered — owns a discovery receiver; also add it to
  `serviceApiNames`, `ApiSmokeTest`, and the live test's router or the catalogue test
  fails). Actions: enable/disable, scan/stopScan, pair. Reports `bonded`, `discovered`.
  **HCI packet read/write is IMPOSSIBLE for a normal app** (needs the stack's HCI socket);
  reports `packetAccess:false` + reason.
- **`wifi_control`**: enable/disable, scan, connect(ssid,password), disconnect. Uses the
  legacy `setWifiEnabled`/`addNetwork`/`getConfiguredNetworks` — **these only work because
  targetSdk is pinned 28**; they silently refuse at targetSdk 29+. Gated on
  ACCESS_FINE_LOCATION (scan results). **Hotspot/USB/BT tethering is IMPOSSIBLE for a normal
  app** (`setWifiApEnabled`/`TetheringManager` are @SystemApi); reports
  `hotspotSupported:false` + reason.
- **`wallpaper`**: `SET_WALLPAPER` (normal). `path` or `url` + `target`=home|lock|both.
  Live: `{"isWallpaperSupported":true,"isSetWallpaperAllowed":true}`.
- **Verified live** on the API 33 emulator: wifi `{"enabled":true,"state":3,...}`,
  bluetooth `{"supported":true,"enabled":true,...}`, wallpaper supported. All
  `AdminApisLiveTest` tests pass.
- New permissions declared: BLUETOOTH_SCAN, BLUETOOTH_ADMIN, CHANGE_WIFI_STATE,
  SET_WALLPAPER. Registry entries: `wifi_control` (ACCESS_FINE_LOCATION),
  `bluetooth_control` (BLUETOOTH_CONNECT, minSdk 31). New tag `hw:bluetooth`.

## Terse commands, admin policies, package dump, OTA loop closed (2026-11-11)
- **Terse command layer.** `debian-container/android-bin/commands.txt` is the human
  command surface (`bt ctrl`, `admin action`, `apps dump`); `apis.txt` stays the wire
  surface. The generator reads both, emits ONE symlink per *command* (45 top-level, 74
  commands) plus a `command_catalog.cpp` dispatch table; `android-bin` resolves
  `argv[0]` + `argv[1]` via `ResolveCommand`. `ApiCatalogTest` fails if the two files
  disagree — it caught `device_info` having no command. Naming rules: terse, no
  underscores, never shadow a real binary (`procs`/`lang`/`sensor` not `ps`/`locale`/
  `sensors`), never a shell keyword as a top-level command (`time` -> `clock`).
- **Device-admin policies**: all 10 tags. **`encrypt-storage` is WRONG — the tag is
  `encrypted-storage`**; a wrong tag fails SILENTLY (framework logs `W DeviceAdminInfo:
  Unknown tag under uses-policies`, enables nothing, `dpm` still says Success). Verify
  with `adb shell dumpsys device_policy` (lists the parsed policies). The 10 are:
  force-lock, wipe-data, reset-password, limit-password, expire-password, watch-login,
  disable-camera, disable-keyguard-features, encrypted-storage, set-global-proxy.
- **Admin-gated but NOT a declared policy**: `installCaCert`/`uninstallCaCert`/
  `installKeyPair`/`removeKeyPair` (gate is "is this an active admin"). `device_admin_action`
  exercises the declared policies; the policy getters THROW without an active admin, so
  the status read must guard them behind the admin check.
- **`packages_dump`** now has exact folders (`dataDir`/`sourceDir`/`nativeLibraryDir`/
  split dirs), parent/children (shared-uid families + APK splits), all components, and a
  `privileged` bool DERIVED from `sourceDir` prefix (`/system/priv-app/`, `/apex/`)
  because `FLAG_PRIVILEGED` is @hide. Live: 185 packages incl. `android`,
  `com.android.shell`, `com.android.systemui`; 6 shared-uid groups.
- **`/v1/apk` is served by `Server.serveDownload` in `apps/api/internal/api/server_handlers.go`,
  NOT the updater handler's `APK`.** I patched the wrong one first. It now redirects
  (302) to the GitHub release asset when the file is not staged locally, via
  `updater.Handler.AssetURL` -> `github.Client.FetchAssetURL`.
- **OTA loop verified end-to-end**: published `app-v0.1.9` (with a `version.json`
  asset); `/v1/check-update` reported 0.1.9 with NO redeploy (server reads the release);
  the Nokia on 0.1.8 checked, got the 302, and downloaded the 73 MB APK. Install still
  needs the user tap.
- **`go vet`/tests break on a new `device.Repository` method**: the `noopDeviceRepo`
  fakes in `internal/ws/hub_test.go` and `tests/testutil_test.go` must implement it.
- Server telemetry now carries `appVersion`; `processTelemetry` writes it via
  `DeviceRepository.UpdateAppVersion`, so `devices.app_version` tracks an OTA.

## OTA resume fix (2026-11-11) — app-v0.2.0
- **Bug**: a network drop mid-download left a partial APK, and the retry called
  `destination.outputStream()` (truncates) -> re-downloaded all 73 MB from byte 0, so a
  flaky link never converged.
- **Fix** (`UpdateClient.download`): resume. A partial file smaller than the manifest's
  `apkSizeBytes` -> `Range: bytes=<len>-` and `FileOutputStream(f, append=true)`.
  `206` appends; `200` to a Range means the server will not resume, so the file is
  rewritten from the start (never append to a 200 body). The manifest checksum still
  verifies the whole file. Progress counts from the resumed offset.
- `File.outputStream(append=)` does NOT exist in Kotlin — use `FileOutputStream(f, append)`.
- **GitHub serves ranges** (`206`), and the `/v1/apk` redirect preserves it: verified
  `curl -L -r 0-99 https://vyzorix.fly.dev/v1/apk/...` -> 206. So resume works end-to-end.
- detekt gotchas hit here: `ThrowsCount` (max 2 throws per function -> extract a helper)
  and `ComplexCondition` (max 3 operands -> split).
- **clang-format is part of `nativeGate`** (NDK's clang-format); new C++ must be
  `clang-format -i`d or the gate fails.
- Published `app-v0.2.0`; server reports it with no redeploy; device picking it up.

## Layer3 asset marker bug (2026-11-11) — app-v0.2.1
- **Symptom**: the device (and emulator) ran the OLD container commands
  (`installed_apps`, no `apps`/`bt`), and `android-bin --api packages_dump` said
  `unknown api`, even after installing the new APK that bundled the new layer3.
- **Root cause**: `Layer3Installer` marks an install with `"$abi:${assetLength()}"`,
  and `assetLength()` uses `AssetManager.openFd`. `noCompress` listed only `"tar.gz"`,
  so AAPT **deflated** `assets/layer3/layer3.l3` — a compressed asset has **no fd**, so
  `openFd` throws, `assetLength()` returns -1, and the marker was the CONSTANT
  `arm64-v8a:-1`. It never changed, so a rebuilt layer3 never reinstalled.
- **Fix**: `noCompress += listOf("tar.gz", "l3")` in `app/build.gradle.kts`. Verified:
  the asset is `STORED` (compress_type 0) in the APK, so `openFd` works and the marker
  gets a real length. `app-v0.2.1`.
- **Lesson**: any asset whose *byte length* or *fd* the code reads must be in
  `noCompress`; AAPT deflation silently removes the fd.
- **Also seen**: the extended-tier download has **no timeout** — on an offline emulator
  the rootfs install thread hangs forever in `openStream()`, so layer3 never installs.
  Worth a connect/read timeout. (The emulator has no internet, which is why the live
  layer3 reinstall could not be shown there.)

## Layer 3 download fallback (2026-11-11) — app-v0.2.2
- `Layer3Installer` now mirrors `RootfsInstaller`: the bundled asset is preferred, and
  when the build does not bundle it the archive is **fetched** from
  `<baseUrl>/layer3-<abi>.tar.gz` and verified against `LAYER3_SHA256`.
- **`LAYER3_SHA256` is hashed from the archive the build produced** (`layer3Sha256()`
  in `app/build.gradle.kts`), NOT read from the rootfs `SHA256SUMS` — that file covers
  only the rootfs tarballs. Reading from it silently gave an empty checksum, which the
  installer then refuses to fetch against (fail-closed, so it would have shipped a
  build that could never download).
- The install marker is `"$abi:${assetLength()}"` for a bundled archive and
  `"$abi:$checksum"` for a fetched one.
- The release now publishes `layer3-arm64.tar.gz` alongside the APK (verified: 206 on
  a range request).
- Test: `tests/android-core/unit/kotlin/com/vyzorix/container/Layer3InstallerTest.kt`
  (Robolectric) covers the fetched path, the no-checksum refusal, the mismatched-bytes
  refusal, and a transport failure — all pass.
- **Kotlin-DSL ktlint** (`:app:ktlintKotlinScriptCheck`) runs on `build.gradle.kts`:
  a KDoc immediately followed by another KDoc fails (`no-consecutive-comments`), and
  double blank lines fail (`no-consecutive-blank-lines`).

## Install prompt + offline text; packages_dump OOM (2026-11-11)
- **"Accept install" action.** The install-ready notification is now **ongoing** and
  carries an explicit `Accept install` action that re-opens the installer for the same
  staged APK, so a dismissed installer prompt can always be retried. `AndroidApkInstaller`
  posts the notification *before* attempting the direct start (so it always exists).
  `DeviceNotification.clearInstall()` cancels it; `UpdateChecker` calls a new
  `onUpToDate` callback only on a **confirmed** "no update available" check
  (`check.versionCode <= currentVersionCode`), wired in `CloudConnectionFactory` to
  `clearInstall()`. So the button persists until the install is actually applied.
  NOTE: `UpdateChecker` does NOT re-post on an already-offered build (the
  `offeredVersionCode` guard returns early) — the standing ongoing notification is
  what keeps the button, which is why the first design sketch (re-post each pass) was
  wrong. Tests: `tests/android-core/unit/.../service/DeviceNotificationTest.kt` (5),
  `tests/cloud-connector/unit/.../update/UpdateCheckerTest.kt` (3).
- **Offline running text** is now exactly `No network connection, running$who but
  offline.` (online stays `Connection stable and running$who.`).
- **`packages_dump` OOMs a low-RAM device.** `apps dump` (the terse command for
  `packages_dump`) with its DEFAULT flags hangs forever on the Nokia C22 — it calls
  `getInstalledPackages(GET_META_DATA|GET_ACTIVITIES|GET_SERVICES|GET_RECEIVERS|
  GET_PROVIDERS|GET_PERMISSIONS)` for all 301 packages, and the device (1.8 GB RAM,
  ~22 MB free, swap already thrashing) OOM-kills it. Measured: `limit=20` instant,
  `limit=40` ok, `limit=150` ok (796 KB), `limit=250` hangs, default (1000→301) hangs.
  `components=false` is fine for all 301 (5 s, 284 KB). It is NOT a shell bug — the
  shell and other APIs (`apps`, `android-bin --api device_info`) answer instantly.
  Candidate fix (not done): bound the default, drop `GET_META_DATA`, or stream.
- **Dumping a big PTY output over the cloud terminal needs credit acks.** The server
  sends PTY data with a 64-frame credit window; a client that does not ack
  (`{"type":"pty_ack","sessionId":..,"credits":1}`) stalls after 64 frames. `/tmp/vyz_run.py`
  is a reusable helper: reattaches a session id, optional Ctrl-C, acks, saves output to
  `/tmp/vyz_out.bin`. `vyzterm.py` leaves each PTY session open on the server (2 h TTL,
  15 min idle) and the cap is 4/device — reattach to an existing session id instead of
  opening new ones. Reattach is allowed for the same operator+device.
- **Nokia C22 dump captured**: 301 packages (66 third-party), 7 shared-uid groups,
  agent on 0.2.1/code 11. Saved at `/workspace/nokia-c22-packages-dump.json`.

## packages_dump limit knob; device-admin verification (2026-11-11, later)
- **`packages_dump` default is the full dump (unlimited, components on).** `limit=<n>`
  caps the page; `components=false` skips the component parse (the flags
  GET_ACTIVITIES/SERVICES/RECEIVERS/PROVIDERS/PERMISSIONS/META_DATA). `limit<=0` or
  absent = no cap. Verified on the Nokia: `apps dump components=false` -> 301 packages,
  truncated=false. NOTE: with components ON the full 301-package dump still OOM-kills
  the 1.8 GB device — so the component dump is inherently a bounded call there; use
  `limit` on such devices. The GETs were kept deliberately (user's call): do not "fix"
  the OOM by removing them.
- **`apps dump` arg syntax**: `apps dump limit=20` (key=value pairs become the JSON args).
- **Device admin works on the Nokia C22.** `admin action` status:
  `isActiveAdmin:true, isDeviceOwner:false, ...`. Round-tripped `setCameraDisabled`
  true->read->false->read and `cameraDisabled` flipped correctly both ways, so the admin
  receiver is genuinely active (not just declared).
- **`resetPassword` (the `setPassword` admin action) is BLOCKED on Android 13.** The
  platform returns `SecurityException: Device admin can no longer call resetPassword()`
  (deprecated at API 26, removed for admins at 29; only a **device owner** may set one).
  The handler already surfaces this honestly as `ok:false, reason:"the platform refused:
  ..."`. So "change the password" is not a proof of admin here — use camera/quality
  policies to prove it, or make the app device owner.
- **`device_owner` on the Nokia**: not owner/profile owner; 1 active admin
  (`com.vyzorix.agent/.service.VyzorixDeviceAdminReceiver`); owner components + lock-screen
  info empty (no owner). `deviceOwnerLockScreenInfo` is empty because no owner ever set it.
- `dumpsys device_policy` is NOT readable from the container shell (returns nothing) —
  use the `admin owner` / `admin caps` APIs instead.

## System-log access: AOSP audit (2026-11-11)
Read from AOSP `platform/system/logging` (main) + `frameworks/base`. The **only** system-log
source is logd, read over the `logdr` socket (what `/system/bin/logcat` uses). Findings:
- **Per-element uid filter is the wall.** `SerializedLogBuffer::FlushTo` (and
  `SimpleLogBuffer::FlushTo`): `if (!writer->privileged() && entry->uid() != writer->uid())
  continue;`. A non-privileged reader sees **only its own uid's entries** — no tags, no
  other processes, no system. Applies to every buffer incl. the events buffer.
- **`privileged` = `clientHasLogCredentials`** (`LogPermissions.cpp`): uid/gid in
  {root, system, log(1007)} OR supplementary group AID_LOG. Also `logcat` pre-checks the
  `READ_LOGS` **runtime** permission, which is `signature|privileged|development`
  (`core/res/AndroidManifest.xml`). Normal app cannot self-grant; only `adb shell pm grant`
  / `appops` (the `development` flag) can.
- **Nokia C22 measured**: process groups `3001 3002 3003 9997 20204 50204` — **no 1007**;
  uid 10204. `/dev/socket/logd*` = Permission denied (SELinux). `/dev/log*` absent,
  `/system/bin/logcat` not visible in the proot container, `/proc/kmsg` denied,
  `dmesg` = ENOSYS. Our `logcat` API returns `forbidden: runtime permission not granted`
  because `PermissionRegistry` gates it on READ_LOGS (SIGNATURE) — our own gate, not AOSP.
- **Events buffer** is exempt from the logd *user-consent* gate (`LogReader.cpp`
  `only_read_event_logs`) but NOT from the uid filter — so even it shows only own entries.
- **The one AOSP leniency (metadata only)**: logd `getSize`/`getStatistics` are NOT gated
  by `clientHasLogCredentials` (unlike setPruneList/clearLog), and `getStatistics` is
  uid-scoped. Yields buffer sizes + own tag names at most; needs the `logd` **control**
  socket, which a normal app's SELinux domain usually cannot connect to.
- **DropBox** (crash/system logs) needs READ_LOGS (<=A14) or READ_DROPBOX_DATA (15+).
- **Conclusion: no practical system-log leak.** The legitimate route is adb `pm grant
  READ_LOGS`, or device-owner/platform-sign/shell — none of which Vyzorix can self-obtain.
- **"Softlock"**: no package literally named softlock on the Nokia; the dump cannot see
  frozen/disabled-by-owner packages if they are filtered from user 0. Definitive source is
  `dumpsys device_policy` + the persistent-apps list, neither readable from the container.

## android-bin subcommand-vs-argument bug (2026-11-11)
- **Bug**: `dev enable=true` → `unknown api 'dev'`. `bridge_main.cpp` decided `argv[1]`
  was a subcommand by *shape* (any non-dash word), consumed it, then `ResolveCommand`
  failed and the fallback used the *command* name as the API. **31 of 38** top-level
  commands broke with any `key=value` arg (`camera x=1`, `apps limit=…` — `apps dump`
  worked only because `dump` IS a subcommand). Fix: a subcommand is consumed only when
  `command argv[1]` *resolves* in the catalog; otherwise it is an argument. Added
  `ResolveCommandTop` (generated) + `src/command_resolve.h` (`ResolveInvocation`, host-
  testable) + `test/command_resolve_test.cpp`, wired into `make check` (links the
  generated `command_catalog.cpp`). The device still runs the OLD binary, so use
  `dev --api developer_options enable=true` until a new APK ships.
- **No arm64 cross-compiler in this session** (`aarch64-linux-gnu-g++` absent) — the host
  `g++` build + `make check` is the available verification. (`scripts/install-toolchain.sh`
  installs it.)

## Developer options on the Nokia C22 (2026-11-11)
- `dev` status: `developerOptions:false, adbEnabled:false, debuggingBlockedByPolicy:false,
  canEnable:true, canWriteSetting:false, isDeviceOwner:false`.
- **`canEnable:true` means only "no policy blocks it"** — the user can still turn it on by
  seven-tapping the build number. Vyzorix cannot: writing `development_settings_enabled`
  needs `WRITE_SECURE_SETTINGS` (`signature|privileged|development|role|installer`) or
  device owner; `canWriteSetting:false` is exactly "this app holds neither".
- **No feature restriction blocks it.** Active user restrictions (5, all Unisoc/operator
  preload): `no_add_user, no_config_tethering, no_factory_reset, no_safe_boot,
  no_usb_file_transfer`. **`disallow_debugging_features` is ABSENT** — that is the only
  one that would block dev options, so nothing does.
- `admin caps` on the Nokia: not owner/profile owner; `canLockNow:false`;
  `storageEncryptionStatus:5` (ENCRYPTION_STATUS_ACTIVE); `lockTaskPermitted:false`; all
  password policy values 0 (an active admin exists but no policies set).
- The container shell has no `settings`/`dumpsys` (Debian, not toybox; SELinux), so the
  bridge APIs are the only way to read these.

## `user_restrictions` API — the whole AOSP restriction catalog (2026-11-11)
- **New API `user_restrictions`** (terse command `restrict`, registered in
  `ApiCatalogue`; `apis.txt` + `commands.txt`). Returns the complete
  `UserManager.DISALLOW_*` vocabulary (70 keys) with a plain-language meaning and a
  `restricted` bool each, plus `restrictedCount`, `active` (map), `unknown` (active keys
  the catalog does not name — OEM additions), `isDeviceOwner`, `canClear`.
- **Why a fixed catalog**: the value is learning about a restriction you did not know to
  ask for, so the keys are enumerated, not discovered from the active set. An active key
  the catalog misses still shows under `unknown`, so nothing active is hidden.
- **Who set a restriction is NOT reported** — that needs `dumpsys device_policy`, which a
  normal app cannot read. Only "this feature is restricted".
- **Read-only** by design: lifting one is `clearUserRestriction`, owner-only; `canClear`
  says whether this app could.
- Test: `tests/android-core/unit/.../bridge/apis/UserRestrictionsApiTest.kt` (5 tests,
  Robolectric). Gotcha: `ShadowUserManager.setUserRestriction(key, bool)` is **protected**;
  the usable overload is the **public** `setUserRestriction(UserHandle, key, bool)`
  (deprecated, so `@Suppress("DEPRECATION")`).
- **`usb state` on the Nokia**: `{"devices":[],"count":0}` — USB **host** features work
  (enumerates, no OTG device attached, which is normal for a phone). `usb ctrl` already
  reported `usbFileTransferBlocked:true`.
- The Nokia's 5 active restrictions: `no_add_user, no_config_tethering, no_factory_reset,
  no_safe_boot, no_usb_file_transfer` (carrier/OEM hardening; setter not observable).

## SystemUI uid, /proc visibility, tethering reporting (2026-11-11)
- **SystemUI's uid**: `com.android.systemui` declares `android:sharedUserId="android.uid.systemui"`.
  On the Nokia it resolves to **10143** (an app-range uid, NOT system 1000) — measured from
  the package dump. (AOSP `android_filesystem_config.h` has AID_SYSTEM=1000, AID_LOG=1007,
  AID_SHELL=2000, AID_APP_START=10000; the `android.uid.*` shared ids take their uid from
  packages.xml.) system_server is the one that runs as **1000**.
- **`/proc` is NOT locked down at targetSdk 28** (verified on the Nokia: 14 numeric dirs
  visible from the app uid; our own process visible). Modern Android (10+) would hide other
  processes' /proc/<pid> from `untrusted_app`. So an app can *enumerate* systemui's pid —
  but cannot read `/proc/<pid>/status` Groups (logd does that check as root, which is why
  logd's own supplementary-group probe still works while ours would not).
- **`process_list` is filtered** by ActivityManager to the app's own process
  (`{"processes":[{"processName":"com.vyzorix.agent",...,"own":true}],"count":1}`) — the
  honest answer, already reported as `visibilityLimited:true`.
- **There is NO read-only "what does SystemUI hide" API.** SystemUI's visibility gates
  (QS tile hidden/disabled, `getUserRestrictions`, `Settings.Secure` policy keys,
  `DISALLOW_*`) are *inputs it reads*, not state it publishes — and reading them needs
  uid 1000 or `READ_DEVICE_CONFIG`/`dumpsys`/`WRITE_SECURE_SETTINGS`, none of which a
  sideloaded app can hold. What an app CAN do: read the same inputs SystemUI reads
  (`UserManager.userRestrictions` -> our `user_restrictions` API; the `Settings.Secure`
  keys) and report *those*, which is exactly the "hidden but operating" surface we can see.
  Reading them from the app's own Context is legitimate (our own settings namespace), but
  the container shell should stay an observer — do not build a "bypass SystemUI" write.
- **`wifi ctrl` now reports tethering accurately** (`tetheringRestrictions`):
  `allTetheringBlocked` / `wifiTetheringBlocked` / `blockedBy` (names
  `no_config_tethering` or `no_wifi_tethering`) / `canUnblock` (owner-only). The Nokia:
  `no_config_tethering:true` -> blockedBy `no_config_tethering`, canUnblock false.
- **Tethering is DOUBLY blocked on the Nokia**: (a) the `no_config_tethering` policy, and
  (b) no public API — `setWifiApEnabled`/`TetheringManager.startTethering` are @SystemApi.
  Even a device owner cannot *start* tethering through public API; only the Settings UI.

## Calls on the Nokia: local stack OK, failure is upstream (2026-11-11)
- **Measured**: `call ctrl` -> `outgoingCallsBlocked:false, callPhoneGranted:true,
  isDeviceOwner:false`. `call caps` -> telephony true, defaultDialer com.google.android.dialer,
  callPhoneGranted true. `phone detailed` -> voiceNetworkType **lte**, dataNetworkType lte,
  roaming false. `phone` -> phoneType gsm, networkOperator Safaricom/63902, simState 5 (READY).
  `dnd` -> interruptionFilter 1 (all), dnd false.
- **A call was placed**: `call ctrl number=000000` -> `placed:true` (ACTION_CALL accepted).
- **No call restriction is set** (no `no_outgoing_calls`, no `no_sms`) — so it is NOT policy.
- **The catch**: all of that proves only the *local* stack. It cannot see the *network side*
  (IMS/VoLTE registration, IMEI blacklist, SIM voice provisioning) — the usual causes when
  "the phone looks fine but calls fail except emergency". `voiceNetworkType=lte` means the
  device believes voice is on LTE (VoLTE); if IMS is not actually up and the carrier refarmed
  2G/3G (Safaricom KE has), calls fail with the stack looking healthy.
- **Gap in our API**: `phone_state_detailed` does NOT read **ServiceState** (IN_SERVICE vs
  OUT_OF_SERVICE vs EMERGENCY_ONLY) nor IMS registration. That is the field that would show
  the radio's own verdict — worth adding (`TelephonyManager.getServiceState()`,
  `ServiceState.isEmergencyOnly()`; IMS via `isImsRegistered()` is @SystemApi/hidden).
- Turned Wi-Fi off as asked: `wifi ctrl action=disable` -> `wifi state` enabled:false state:1.

## Overlay permission resets + build-number tap on the Nokia (2026-11-11)
- **"Display over other apps" = the `SYSTEM_ALERT_WINDOW` app-op**, stored in
  `/data/system/appops.xml`. It is NOT a runtime permission and NOT a `UserManager`
  restriction, so `dev` / `restrict` / `admin` CANNOT show it — different subsystem.
  Only Settings, `cmd appops`/adb, a device owner (API 31+ `setPermissionGrantState`),
  or the OS itself can change it.
- **Our app is not the one resetting**: it does **not** declare `SYSTEM_ALERT_WINDOW`
  (verified: no manifest entry, no `PermissionRegistry` entry, no appops code). So the
  resets affect *other* apps on the device.
- **The heavy overlays here are sideloaded third-party apps**, not system ones:
  `com.treydev.pns` (Power Shade, an a11y service), `nu.nav.bar` (Navigation Bar, a11y
  service). Android revokes `SYSTEM_ALERT_WINDOW` for an app in **"restricted settings"**
  (sideloaded, no Play) when the screen locks — that is the lock-screen reset.
- **OEM actors that "clean" state on lock/doze**: `com.hmdglobal.memorycleaner` ("Memory"),
  `com.sprd.linkturbo` (Unisoc), `com.google.android.apps.turbo` (Device Health). The
  Unisoc+HMD stack is aggressive; any of these resetting app state would look like a
  permission reset.
- **Cannot confirm from the app**: appops live in `/data/system/appops.xml` (system/root
  only), `cmd` is not in the container, `dumpsys appops` is SELinux-blocked. No bridge API
  can read another app's overlay grant (`canDrawOverlays` is own-package only).
- **The 7-tap-does-nothing**: most likely the OEM/carrier customized the build-number
  gesture, or a managed/provisioned state we cannot see (no owner is visible, but a carrier
  DPC could be hidden). It is NOT `no_debugging_features` (absent) — which is why `dev`
  reports `canEnable:true` while the tap is refused. AOSP Settings has "Restricted setting"
  (`blocked_by_restricted_settings_title` / "Allow restricted settings") for sideloaded
  apps; that gate is inside the Settings app and is not in `UserManager`, so our API is
  blind to it.
- **Net**: this is a locked-down retail/carrier device (the `no_config_tethering` /
  `no_usb_file_transfer` bundle + aggressive cleanup + restricted-settings enforcement).
  Verifying any of it needs `adb shell dumpsys appops` / `dumpsys device_policy` from a
  host with the phone attached — the container cannot see it.

## app_ops + accessibility write, verified live (2026-11-11)
- **`app_ops` API** (terse `ops`): reads this package's `AppOpsManager` modes. **CRITICAL
  GOTCHA**: `checkOpNoThrow(String, uid, pkg)` keys on the **`OPSTR_*` value**
  (`android:system_alert_window`), NOT the constant name (`SYSTEM_ALERT_WINDOW`) — passing
  the name throws `IllegalArgumentException: Unknown operation string`. The unit test
  PASSED wrongly because Robolectric's AppOpsManager shadow cannot resolve op names at all
  (everything reads `unknown`); the **live test on API 33 caught it** (all ops `unknown`).
  Fix: use the literal `OPSTR_*` values. Most `AppOpsManager.OPSTR_*` constants are NOT on
  the public SDK (unresolved at compile), so the literals are used — AOSP-stable, and what
  `dumpsys appops` prints. Verified live on API 33: `SYSTEM_ALERT_WINDOW=default`,
  `RUN_IN_BACKGROUND/WAKE_LOCK/START_FOREGROUND=allowed`, `POST_NOTIFICATION=ignored`,
  `READ/WRITE_EXTERNAL_STORAGE=ignored`, `SEND_SMS/RECEIVE_SMS=ignored`, deniedCount 10.
  Lesson: an app-op test on Robolectric is near-worthless — it must run on a device.
- **`accessibility` write**: `action=enable|disable component=pkg/Class` rewrites
  `Settings.Secure` `enabled_accessibility_services` (colon-separated). Needs
  `WRITE_SECURE_SETTINGS` (signature) — a **device owner does NOT get it** (no secure path,
  unlike a global setting). Reports `canWrite`, and on refusal `changed:false` + reason.
  Verified live: on the API 33 emulator (which grants WRITE_SECURE_SETTINGS to a debug
  build) the toggle actually landed — `changed:true`, `enabled` gained `com.example/.Svc`.
  On the Nokia it will report `canWrite:false` and refuse.
- **Live-test harness**: `AdminApisLiveTest` now covers user_restrictions, app_ops,
  accessibility (read + refused/allowed toggle), systemui_state, packages_dump limit.
  Run: `./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64
  -Pandroid.testInstrumentationRunnerArguments.class=com.vyzorix.agent.AdminApisLiveTest
  -x :app:buildLayer3 -x :app:stageLayer3 -x :app:stageRootfs` (the -x's skip the missing
  cross-compiler). 18 tests, all pass on API 33. **Boot the emulator with `api33`**
  (`emulator -avd api33 ... -accel off`, ~4-5 min; `x86test` is the other AVD).

## Unisoc/ADB/launch recon on the Nokia (2026-11-11)
- **Unisoc EngineerMode/YLog are STRIPPED.** `com.sprd.engineermode` and
  `com.sprd.logmanager` are **absent** from the Nokia's package dump — HMD removed them
  (the rest of the Unisoc stack is present: `com.sprd.srmi`, `com.sprd.linkturbo`,
  `com.unisoc`, `com.spreadtrum.ims`). So `*#*#83781#*#*` / `am start -n
  com.sprd.engineermode/.EngineerModeActivity` has nothing to open. The dialer-code method
  is dead because the app is gone, not because the dialer blocks it.
- **The container has NO system tools**: only our `logcat` wrapper. `am`, `pm`, `settings`,
  `cmd`, `getprop`, `dumpsys`, `toybox`, `adb` are all **absent**; `/system/bin` is not
  visible (proot bind). So every "terminal host" command must go through our app-uid bridge.
  `getprop`/`dumpsys`/`settings` are unreachable from the device; the bridge APIs are the
  only read path.
- **`startActivity` from the app process is SILENTLY DROPPED** (Android 10+ background
  activity start block). PROVEN on the API 33 emulator: `systemui_state action=launch
  intent=android.settings.APPLICATION_DEVELOPMENT_SETTINGS` returned **`started:true`**
  (no exception) but the top activity stayed `com.android.launcher3/.uioverrides.
  QuickstepLauncher` before AND after — the screen never changed. So `started:true` means
  only "the call did not throw"; the activity never reached the screen. This is the same
  wall the OTA installer hit (fixed there by a notification the user taps). **The app
  cannot drive a system UI this way**; the only user-initiated route is a notification tap
  (or a foreground/visible activity). Detection used `UiAutomation.executeShellCommand
  ("dumpsys activity activities")` + the `topResumedActivity=` line.
- **Added `systemui_state` `launchTargets`** (read-only `am start -n` equivalent):
  probes `com.sprd.engineermode`, `com.sprd.logmanager`, and two Developer-Options
  components, reporting `installed`/`resolvable` each. On the emulator:
  EngineerMode/LogManager `installed:false`; Settings `installed:true` but the dev-options
  classes `resolvable:false` (name differs per build — use `resolveActivity`, not a guessed
  class). Plus `action=launch component=|intent=`.
- **`device_info` now reports `lowRam` / `memoryClassMb` / `largeMemoryClassMb`**
  (`ActivityManager.isLowRamDevice()` — the Android Go signal). Live test added.
- **DEVICE_PROVISIONED / USER_SETUP_COMPLETE were fine on the emulator** (`1`/`1`); the
  Nokia's `dev` shows `canEnable:true` but the build-number tap is refused — the gate is
  OEM/carrier, not `no_debugging_features` (absent).
- **ADB wireless from the device is impossible**: no `adb` binary, no `cmd`, no root —
  wireless debugging needs a host (Settings UI + `adb pair`) we cannot reach. `persist.sys.usb.config`
  is not readable from the app (getprop unreachable), so the "locked by build config"
  claim cannot be confirmed from the device.

## Privilege-escalation landscape (2026) + `escalation_recon` API (2026-11-11)
Researched live on GitHub. The routes the open-source community uses to get >app privilege:
- **Shizuku** (RikkaApps/Shizuku, 33k★): a server run via `app_process` under **ADB** (or
  root), which then **proxies binder calls** to system services on the app's behalf. The app
  holds Shizuku's binder and calls `transactRemote`/`ShizukuBinderWrapper`; it talks to
  system services *as the Shizuku uid*. Apps: **Canta** (uninstall any app),
  **Hail** (freeze/disable/hide/suspend), **App Manager**, **aShell**. ADB's permission set
  is fixed and limited (`packages/Shell/AndroidManifest.xml`).
- **Sui** (RikkaApps/Sui): the Magisk/root variant of Shizuku.
- **Dhizuku** (iamr0s/Dhizuku, 4.1k★): **shares a device-owner's privileges** to other apps
  via a binder + `com.rosan.dhizuku.permission.API`. Needs the owner role bootstrapped once.
- **Device owner / DPC**: OwnDroid (1.4k★), testdpc. Bootstrapped at setup (NFC/QR) or
  `adb shell dpm set-device-owner`; can uninstall/hide/disable packages and set most policy.
- **Local-ADB apps** (aShellYou, LADB, TDA-Mox): an on-device adb client — but connecting
  to **wireless debugging requires the user to enable it and supply a pairing code**, so
  they do not self-bootstrap either.
- **Universal Android Debloater** (desktop) + a device: drives `pm uninstall --user 0` over
  **ADB** from a PC.
- **KEY TRUTH**: every route needs a **user/ADB/root bootstrap the app cannot perform on
  itself**. The old "Shizuku loopholes patched" story = Android tightening background
  activity starts, hidden-API access (P+), and ADB's own permission set. **No current route
  lets a plain app reach a privileged uid by itself.**
- **New API `escalation_recon`** (terse `esc`): read-only. Reports own uid, whether
  Shizuku/Sui/Dhizuku are installed + their permission granted, root `su` binaries present,
  `isDeviceOwner`, `canWriteSecure`, `adbEnabled`. It does **not** escalate — no shell, no
  provider contact. The honest "what routes exist here" probe.
- **On the Nokia**: none of these are installed (no Shizuku/Sui/Dhizuku), not rooted, not
  owner — so no escalation route is present, matching everything else (stripped engineer
  apps, locked user build). The community path for that device would be: enable wireless
  debugging → pair from a PC → `adb shell sh /data/local/tmp/shizuku` (or `pm uninstall`).

## Bundled adb / wireless-debug-from-container: feasibility (2026-11-11)
Goal: bundle a modern `adb` in the container so the user enables Wireless Debugging and
pairs on-device (LADB-style), no PC. Verdict: **doable, but a cross-build project.**
- **Debian's `android-tools-adb` is DEAD for this**: Debian ships **5.1.1** (buster era,
  `sources.debian.org` shows 5.1.1.r38 only; absent from bookworm/trixie). `adb pair` needs
  adb **30+** (Android 11). No `pair`, no `mdns`. Unusable.
- **Termux's prebuilt** (`android-tools` 37.0.0p1, aarch64 .deb) is **bionic/libc++** — it
  will NOT run in our glibc Debian container. Unusable.
- **So adb must be cross-built for the container's glibc (bookworm 2.36).** Source:
  `nmeum/android-tools` (CMake, vendors adb+boringssl+libusb+fmt; `libprotobuf`, `brotli`,
  `lz4`, `zstd`, `pcre2` external). Host build PROVEN here (release 37.0.0p1, `pair`/`mdns`
  present). **Cross build needs a full aarch64 dep set** (protobuf + abseil + brotli + lz4 +
  zstd + pcre2 for the target) — a real sysroot job, not an afternoon.
- **Design**: ship it in layer3 (the overlay's `/usr/local/bin`, like `android-bin`), or a
  separate asset. It is **dynamic** (~25 MB + deps) — the layer3 gate requires *static*
  binaries, so either fully static-link it (boringssl+protobuf static is big) or relax the
  gate for it and ship the .so set. Both are real work.
- **Payoff is bounded**: adb runs as the **shell** uid — a *fixed, limited* permission set
  (`packages/Shell/AndroidManifest.xml`). It can `pm uninstall --user 0`, `pm disable-user`,
  `am`, `dumpsys`, `settings put`, `appops set`. More than the app, but NOT system, and
  nothing close to "uninstall any package".
- **Prereq on a real device**: the user must enable **Wireless Debugging** (needs Developer
  Options — on the Nokia the build-number tap is refused, so this feature may be moot there).
  `adb pair <ip:port> <code>` needs the pairing dialog's code; `adb connect` needs the
  (changing) debug port. The pairing/connect flow is all in-container once adb exists.
- **No off-the-shelf static adb exists** (searched GitHub — nothing). Termux is the only
  maintained prebuilt and it is bionic.

## adb-as-an-asset + wireless debug (2026-11-11)
Built the whole flow (NOT baked — published as a release asset, installed on demand):
- **`scripts/build-adb.sh`** — builds `adb-<arch>.tar.gz` (+ `.sha256`) into
  `debian-container/adb/dist/` (gitignored). Approach: **debootstrap a bookworm chroot for
  the target arch and build `nmeum/android-tools` INSIDE it** (through qemu-user for
  arm64/armhf). Building natively in the target sidesteps hand-assembling a cross sysroot
  for adb's many deps. Verified end-to-end for x86_64: 12 MB tarball, `bin/adb` +
  `bin/adbwireless` + `lib/*.so`, and `adb version` = 37.0.0p1 with `pair`/`mdns`.
  - Chroot build deps: `build-essential cmake ninja-build pkg-config libprotobuf-dev
    protobuf-compiler libabsl-dev libbrotli-dev liblz4-dev libzstd-dev libpcre2-dev
    libusb-1.0-0-dev libgtest-dev`. **`libgtest-dev` is required** — `libziparchive`
    includes `gtest/gtest_prod.h`; without it the build fails at zip_writer.cc.
  - The tarball ships only the NON-system `.so`s (brotli, lz4, protobuf, zstd, z); libc and
    libstdc++ come from the rootfs. The helper sets `LD_LIBRARY_PATH`.
- **`debian-container/adb/adbwireless`** — the container helper: `pair <ip:port> <code>`,
  `connect [ip:port]`, `status`, `kill`. Sets `LD_LIBRARY_PATH` to the sibling `lib/`.
- **`wireless_debug` API** (terse `wdbg`): reads `Settings.Global.adb_wifi_enabled`,
  `adb_enabled`, and the device's IPv4 addresses. **The pair port and code are NOT readable
  by any app** (Settings dialog only) — reports `portReadable:false` + command hints, the
  honest LADB-style flow (user reads the code off the screen).
- **Publishing**: `gh release upload <tag> adb-<arch>.tar.gz adb-<arch>.tar.gz.sha256` —
  the same release as the rootfs/layer3 assets (must keep `--latest=false`).
- **Install-on-demand** (the "install if you need it" ask): a container-side fetch of the
  asset (curl from the release URL, verify the `.sha256`, extract at `/usr/local`) — the
  helper or a future `adb-install` command does it; it is deliberately NOT in the APK.
- CI/gate: `./gradlew gate` green; CLI rebuilt (50 symlinks incl. `wdbg`).
