# Vyzorix Android — Headless Service Architecture

**Status:** v3 — agreed design, pre-implementation
**Scope:** the Android agent (separate repository from this server repo)
**Target:** headless Android service running a Debian Bookworm userspace + PTY + Android
Gateway + Cloud WebSocket; scales to 120+ Android APIs and 100+ devices without
rearchitecting.

This document is the authoritative design for the Android side. The server side
(`vyzorix-update-server`) already provides the command plane, signing, audit, and risk
tiers referenced throughout; see `DOCUMENTATION/COMMAND_EXECUTION.md` and
`DOCUMENTATION/RISK_AND_CONFIRMATION.md`.

> **v3 note:** This revision corrects five platform-level blockers found in v2 (exec
> restriction, storage paths, cloud auth, dual WebSocket ownership, PTY allocation) and
> four structural conflicts (immutable-vs-apt, `0.0.0.0` exposure, PTY framing,
> boot/FGS reality). Every corrected claim is backed by AOSP source or by this server's
> own code. See §13 for the change log.

---

## 0. The one idea that makes this work

**Everything privileged is an fd-capability owned by Layer 1.**

PTY, USB, camera, sockets — Layer 1 (Kotlin + NDK) acquires the kernel resource and
holds the file descriptor. Layers above never open device nodes; they receive fds over a
unix socket (`SCM_RIGHTS`) or inherit them via `fork`/`exec`.

This single pattern solves the two hardest problems in the design:

- **PTY allocation inside proot is not guaranteed** → allocate outside, pass the fd in.
- **`/dev/bus/usb` is unreachable unrooted** → get the fd from `UsbManager`, wrap with
  libusb.

It also gives a clean privilege boundary: the capability grant *is* the authorization,
and it is auditable.

---

## 1. Verified platform constraints

These are not opinions. They shape the design and must not be worked around.

### 1.1 `targetSdk` must be 28 — a platform constraint, not a choice

The mechanism is SELinux domain mapping, enforced by the kernel, not a Java-level check:

```
# system/sepolicy/private/seapp_contexts
user=_app minTargetSdkVersion=34 domain=untrusted_app    type=app_data_file ...
user=_app minTargetSdkVersion=32 domain=untrusted_app_32 type=app_data_file ...
user=_app minTargetSdkVersion=30 domain=untrusted_app_30 type=app_data_file ...
user=_app minTargetSdkVersion=29 domain=untrusted_app_29 type=app_data_file ...
user=_app minTargetSdkVersion=28 domain=untrusted_app_27 type=app_data_file ...

# system/sepolicy/private/untrusted_app_27.te
allow untrusted_app_27 app_data_file:file execute_no_trans;   # exec permitted
```

`untrusted_app_29` and above **lack** that rule. Therefore:

- `targetSdk <= 28` → domain `untrusted_app_27` → executing binaries from app data works.
- `targetSdk >= 29` → no exec from app data, and **no userspace workaround exists**.

This matches the platform documentation: *"Untrusted apps that target Android 10 cannot
invoke `execve()` directly on files within the app's home directory."*
([Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10))

Our Debian binaries live in app storage. The `jniLibs` trick only makes the `proot`
binary itself executable (the native lib directory is executable); it does not save the
rootfs. Precedent: Termux pins `targetSdkVersion=28` in `gradle.properties` for exactly
this reason, and was consequently removed from Play.

**Pinned values:**

```
minSdkVersion     26    # Android 8.0 — the floor the code actually has (see §1.1)
targetSdkVersion  28    # hard constraint — see above
compileSdkVersion 35    # compile high, target low: new APIs available to compile against
```

**Consequences:**

- Play Store is impossible (Play requires a recent API level, rising yearly).
  Distribution is sideload / F-Droid / direct APK, permanently.
- Android 14's minimum *installable* target is 23, so a targetSdk-28 app still installs
  on Android 14/15/16. The risk is a future Android raising that floor past 28.
- The escape hatch is the root backend (§4.4), not a userspace trick.
- Guard the exec path: on `EACCES`, surface "this Android version blocks it; a root
  backend is required", not a cryptic crash.

### 1.2 Writable storage is app-private, never `/data/local`

AOSP `rootdir/init.rc`:

```
mkdir /data/local      0751 root  root
mkdir /data/local/tmp  0771 shell shell      # SELinux: shell_data_file
```

`/data/local` is root-owned `0751` → an app has execute but **no write**.
`/data/local/tmp` is owned by the **shell** UID and labelled `shell_data_file` → app
domains cannot write it either.

**All runtime state lives under the app data directory:**

```
/data/data/dev.vinns.vyzorix/files/            # rootfs, overlay, sockets, config  (0700)
/sdcard/Android/data/dev.vinns.vyzorix/files/  # OPTIONAL export target only
/data/local/tmp/                               # STAGING ONLY (ADB-pushed), never runtime home
```

**`/sdcard` is emulated internal storage, not a removable card.** It is present on every
Android device; the app never requires an SD card. Shared storage is also mounted
`noexec`, so executables can never live there. See §6.1.

### 1.3 Cloud auth is HMAC, not a bearer token

Verified in `apps/api/internal/api/server_routes.go`: the device management group is
wrapped in `RequestSigningMiddleware` + `MandatoryEncryptionMiddleware` + `requireHMAC()`,
and the WebSocket upgrade calls `hmacVerifier.VerifyWebSocketConnect(r, deviceID)`.

**Devices sign their requests** (timestamp + nonce + replay guard) with the per-device
secret. There is no `?token=<jwt>` handshake. A permanent JWT in a URL would also leak
into access logs and proxy history — do not do it.

### 1.4 Background survival: the OEM killer is the risk, not the phantom killer

- Phantom process killer: `DEFAULT_MAX_PHANTOM_PROCESSES = 32` (AOSP
  `ActivityManagerConstants`), gated by
  `SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS = "settings_enable_monitor_phantom_procs"`
  (default `true`, persistent flag). It reaps **orphaned** processes.
- Our processes are children of a live foreground service, so they are **not** phantom.
  This is good news and worth stating explicitly.
- The real threat is **OEM battery managers** (Xiaomi/Huawei/Samsung) and Doze killing
  the foreground service itself.

**Mitigations:** `START_STICKY`, battery-optimization exemption request, high-priority
FCM wake, and a watchdog that restarts the engine with crash backoff.

### 1.5 Foreground service type must be declared

Android 14 requires a declared type and permission **only for apps that target Android
14** — so at targetSdk 28 the `MissingForegroundServiceTypeException` does not apply to
us. Declare it anyway: it is correct, and it costs nothing.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<service android:name=".GatewayService"
         android:foregroundServiceType="specialUse"
         android:exported="false">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="device_management_agent" />
</service>
```

Reference:
[Android 14 FGS types](https://developer.android.com/about/versions/14/changes/fgs-types-required).

---

## 2. Layer contract (4 layers, no "2.5")

```
Android OS (6 -> 15+; Samsung / Xiaomi / Nokia)
│
├─ Layer 1 — Android Gateway      (Kotlin + NDK)   <- the ONLY layer that touches the OS
│     FGS owner · boot · watchdog · cloud WS · PTY broker · USB broker · android.sock
│
├─ Layer 2 — Debian Engine        (proot, read-only base + writable overlay)
│     bash · android-* bridge · pty-agent (local only) · toolchain
│
├─ Layer 3 — App Runtime          (C supervisor + bash wrappers)
│     app lifecycle · inotify · http_proxy · persistent /home/apps
│
└─ Layer 4 — Cloud Connector      (lives INSIDE Layer 1, not a second client)
      HMAC-signed device WS · heartbeat · command dispatch · app proxy relay
```

**Invariants:**

- Layer 1 is the only process that talks to Android APIs and the only holder of the
  cloud WebSocket.
- Layers 2–3 speak **local unix sockets only**. They never dial the cloud.
- Layer 2 is disposable (replaceable tarball). Layer 3's storage is not.
- Every layer is testable against a **mock of the layer below** (Layer 2 tests against a
  fake `android.sock`, not a real phone).

---

## 3. Folder structure

```
Vyzorix-android/
├── android-core/                        # Layer 1 — Kotlin + NDK
│   ├── GatewayService.kt                # FGS, START_STICKY, watchdog, owns all layers
│   ├── BootReceiver.kt                  # BOOT_COMPLETED -> start FGS
│   ├── cloud/
│   │   ├── DeviceWsClient.kt            # THE single cloud WS (HMAC handshake)
│   │   ├── Heartbeat.kt                 # ping 30s, reconnect + backoff
│   │   └── CommandDispatcher.kt         # cloud frame -> local socket
│   ├── bridge/
│   │   ├── AndroidSocketServer.kt       # files/android.sock  {"api":...,"args":...}
│   │   ├── SessionTokens.kt             # per-session capability tokens (§9)
│   │   ├── ApiRouter.kt                 # apiName -> handler (1 registration line)
│   │   └── apis/                        # one file per Android API
│   │       ├── CameraApi.kt  LocationApi.kt  BatteryApi.kt  UsbApi.kt  ...
│   ├── capability/                      # NDK — the fd layer (§4)
│   │   ├── PtyBroker.kt                 # allocate PTY, hold master fd
│   │   ├── pty_native.cpp               # posix_openpt / grantpt / unlockpt, setsid, TIOCSCTTY
│   │   ├── UsbBroker.kt                 # UsbManager -> getFileDescriptor()
│   │   ├── usb_native.cpp               # libusb_wrap_sys_device
│   │   └── FdPass.kt                    # SCM_RIGHTS send/recv over unix sockets
│   ├── compat/                          # v6 -> v15
│   │   ├── PermissionCompat.kt  HotspotCompat.kt
│   │   ├── ServiceCompat.kt     StorageCompat.kt
│   │   └── OemCompat.kt                 # battery-exemption prompts per vendor
│   └── permissions/                     # all requests in one place
│
├── debian-engine/                       # Layer 2
│   ├── rootfs/
│   │   ├── debian-core.tar.gz           # minbase + bash,coreutils,socat,curl,ca-certs
│   │   └── debian-extended.tar.gz       # optional, on-demand extract
│   ├── overlay/                         # writable prefix, survives base updates (§6.1)
│   │   └── usr-local/  opt/  etc/       #   bind -> /usr/local, /opt, /etc/vyzorix
│   ├── proot-launcher.sh                # binds + -0 + resolv.conf
│   ├── first-boot.sh                    # extract, seed overlay, verify bash
│   ├── android-bin/
│   │   ├── apis.txt                     # camera, location, battery, ... (120)
│   │   ├── generator.go                 # 1 binary + N symlinks
│   │   └── src/bridge.cpp               # forwards JSON -> android.sock
│   └── pty-agent/                       # Go — LOCAL multiplexer only
│       ├── main.go                      # listens on files/pty.sock
│       ├── pty_manager.go               # tracks sessions, resize, tmux attach
│       └── frames.go                    # binary framing (§6.3)
│
├── app-runtime/                         # Layer 3 — inside Debian
│   ├── supervisor/
│   │   ├── supervisor.c  app_manager.c  socket_server.c  http_proxy.c
│   │   ├── apps.json                    # {name,dir,cmd,port,env,autostart,pid,pid_start_ticks}
│   │   └── Makefile
│   ├── cli-wrappers/                    # /usr/local/bin inside Debian
│   │   ├── app-start  app-stop  app-restart  app-list
│   │   ├── app-logs   app-env   app-create   app-expose
│   └── web-ui/                          # offline page on the device
│
└── cloud-connector/                     # Layer 4 — thin; logic lives in Layer 1
    ├── Endpoints.kt                     # /v1/... constants (§5)
    └── AppProxyRelay.kt                 # http_proxy frames <-> supervisor
```

---

## 4. The fd-capability model

### 4.1 PTY — allocate in Layer 1, pass the fd down

Do **not** rely on `/dev/ptmx` being reachable inside proot. Instead:

1. Layer 1 (NDK) opens a PTY pair: `posix_openpt` → `grantpt` → `unlockpt` → `open(slave)`.
2. A tiny native launcher does `setsid()` + `ioctl(slave, TIOCSCTTY, 0)` and `dup2`s the
   slave onto fd 0/1/2.
3. It `exec`s `proot-launcher.sh`, which execs `bash`.
4. **Layer 1 holds the master fd** and does all I/O on it. Layer 2 never opens `/dev/ptmx`.

Result: PTY allocation works regardless of what proot can see, and the session's I/O
endpoint is a capability Layer 1 controls (and can revoke by closing).

### 4.2 USB — `UsbManager` fd → libusb

1. Kotlin: `UsbManager.getDeviceList()` → `requestPermission()` → `openDevice()` →
   `connection.getFileDescriptor()`.
2. NDK: `libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL)` →
   `libusb_init()` → `libusb_wrap_sys_device(ctx, fd, &handle)`.
3. Expose the fd to Layer 2 over a unix socket via `SCM_RIGHTS`, so Debian-side tools
   (`esptool`, `pyusb`, `avrdude`, custom translators) use it directly.

This is the documented Android path. libusb's header states `NO_DEVICE_DISCOVERY` is
*"typically needed on Android, where access to USB devices is limited"* and is *"useful
in combination with `libusb_wrap_sys_device()`"*. The reference implementation is
`libusb/android/examples/unrooted_android.c`.

**Known limits (document, do not fight):**

- Kernel driver auto-claim (`cdc_acm` on serial adapters) blocks `claimInterface`;
  detaching needs root.
- Isochronous transfers are weak → fine for flashing/dumping, bad for USB audio/video.
- OTG supplies roughly 100–500 mA; high-draw devices need a powered hub or Y-cable.

### 4.3 Why not `/dev/bus/usb`

The nodes exist on the host, but SELinux policy for untrusted app domains denies access.
proot fakes `getuid()`, but it only rewrites *path arguments* — it cannot rewrite what the
kernel enforces. Binding a path that is inaccessible to the app's domain gains nothing.
The fd route above bypasses the path restriction entirely, which is why it is the design.

### 4.4 Execution backends (roadmap)

One engine, three execution backends behind the same `ExecutionBackend` interface.
Define the interface now; implement only `proot` in v1.

| Backend | Status | Gains | Needs |
|:--------|:-------|:------|:------|
| **proot** | v1.0 — supported, tested | Userspace root, Debian FHS, apt/dpkg | nothing |
| **shizuku** | Planned, post-v1.0 stability | `pm`/`am` control; can disable the phantom-process killer | Shizuku running (shell UID 2000) |
| **root** | Planned, post-v1.0 stability | Real `/dev/bus/usb`, `mount`, kernel-driver detach | Magisk |

Layer 2 and Layer 3 code is identical across all three; only the launcher differs. This
abstraction is the insurance policy against a future Android raising the targetSdk floor
past 28.

---

## 5. Cloud connector — real endpoints

All paths verified against `apps/api/internal/api/server_routes.go`.

| Purpose | Endpoint | Auth |
|:--------|:---------|:-----|
| Device WS (commands + PTY + proxy) | `GET /v1/device/:imei/stream` | HMAC-signed handshake |
| Register (new device) | `POST /v1/device/inbox` | public + idempotency |
| Inbox status / ack | `GET /v1/device/inbox` · `POST /v1/device/inbox/:imei/ack` | org-scoped |
| Pending commands | `GET /v1/device/:imei/commands/pending` | HMAC |
| Command status | `GET /v1/command/:dispatchId/status` | HMAC |
| FCM token update | `PATCH /v1/device/:imei/fcm-token` | HMAC |
| APK download | `GET /v1/apk/*name` | public |
| Version / update | `GET /v1/version` · `GET /v1/check-update` · `POST /v1/download-progress` | public |
| Command result (HTTP fallback) | `POST /v1/device/:imei/command/:dispatchId/result` | HMAC |
| Operator terminal | `GET /v1/device/:imei/terminal` | session cookie + `shell.execute` |

**Command framing is already signed.** The server HMAC-SHA512-signs each `CommandFrame`
(nonce + timestamp) with the device secret; the device verifies before executing. Reuse
that — do not invent a second signing scheme.

**New command types** (register in `internal/domain/command/risk_catalog.go` on the
server side):

| Command | Tier | Notes |
|:--------|:-----|:------|
| `SHELL_EXEC` | high | one-shot, stateless — Phase 0, ships first |
| `USB_ATTACH` | critical | MFA + confirmation; destructive/irreversible |
| `APP_START` / `APP_STOP` | medium | supervisor control |

**The server does not execute commands, and there is no server-side handler for
`SHELL_EXEC` — that is by design, not a gap.** The server is a command plane.
For every command type its job is the same four steps: authorize against the
risk profile, sign the frame with the device secret, deliver it over the device
WebSocket (or an FCM silent wake), and record the result the device reports
back. The *device agent* executes. A server-side handler would mean the server
ran shell commands, which is impossible (it has no access to the device) and
undesirable (it would bypass the device's own privilege model).

So `SHELL_EXEC` is classified (for the risk gate) and dispatched (to the
device) — never executed server-side. Two consequences for the device agent:

- **It must implement `SHELL_EXEC`.** A `high`-tier one-shot command whose
  output is returned through the `command_result` path (§6.3 covers the frame
  shape for PTY; the same result path applies here).
- **It must not silently ignore an unknown type.** If a device receives a
  command it does not implement, it should report a **failed** result rather
  than drop it, so the operator learns the command was not executed instead of
  watching it sit `delivered` forever.

Do not add an allowlist of "known" command types on the server. The device
agent owns that knowledge; the server's job is to authorize and deliver. A
server-side list would reject every new command until a server deploy shipped.

**Interactive shells are not a command.** An earlier revision listed
`PTY_SESSION_OPEN` here. The implemented design opens a session over the operator
WebSocket instead (`GET /v1/device/:imei/terminal`, gated on the `shell.execute`
scope), because a shell is stateful and bidirectional and does not fit the
fire-and-forget command lifecycle. See §6.3 for the contract.

**Exactly one WS owner.** Layer 1 (`DeviceWsClient.kt`) owns it. `pty-agent` and the
supervisor never dial the cloud; Layer 1 relays. This kills the v2 three-way ownership
conflict.

---

## 6. Debian engine

### 6.1 Rootfs: read-only base + writable overlay

The base image is immutable and replaced wholesale. On-device installs go to a separate
overlay directory that survives base replacement. proot has **no `mount`**, so there is
no overlayfs; the overlay is a set of bind mounts and `PATH` precedence.

```
files/rootfs/          app-private    read-only base (replaced on update)
files/overlay/         app-private    writable prefix (survives updates)
files/android.sock     app-private    API bridge
files/pty.sock         app-private    PTY multiplexer
files/config.json      app-private    device identity
/sdcard/Android/data/dev.vinns.vyzorix/files/   OPTIONAL export target
```

**The overlay must be app-private.** It holds binaries and libraries; shared storage is
mounted `noexec` (vold/MediaProvider FUSE), so nothing there can be executed, and it is
slower. This is a constraint, not a preference.

```
overlay/usr-local -> /usr/local
overlay/opt       -> /opt
overlay/etc       -> /etc/vyzorix
```

`PATH` and `LD_LIBRARY_PATH` place the overlay first. `apt install` is discouraged
on-device; `vyzorix-pkg` extracts into the overlay, or `apt-get install -o RootDir=…`
when online. **Base updates never touch user installs.**

**No SD card is required at any point.** `/sdcard` is emulated internal storage, present
on every device. It is used only as an optional export target for artifacts (for example,
a dump file a technician wants to grab with a file manager), never as a dependency.
Because we target SDK 28, `requestLegacyExternalStorage` is honoured and broad `/sdcard`
access comes from `READ/WRITE_EXTERNAL_STORAGE` alone; `MANAGE_EXTERNAL_STORAGE` is not
required.

### 6.2 proot launch

```bash
proot --kill-on-exit --link2symlink -0 \
  -r "$FILES/rootfs" \
  -b /dev -b /proc -b /sys \
  -b "$FILES/overlay/usr-local:/usr/local" \
  -b "$FILES/overlay/opt:/opt" \
  -b "$FILES/rootfs/etc/resolv.conf:/etc/resolv.conf" \
  -b "$FILES/android.sock:/tmp/android.sock" \
  -b "$SDCARD/Android/data/dev.vinns.vyzorix/files:/sdcard" \
  -w /root \
  /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/bash --login
```

Notes:

- `-0` fakes userspace root (sufficient for apt/dpkg).
- `resolv.conf` **must** be bound, or there is no DNS.
- The socket path is `files/android.sock` everywhere; v2 had two different paths.

### 6.3 PTY contract — implemented on the server

The server side of this contract is **built** (`apps/api/internal/ws/pty_frame.go`,
`pty_registry.go`, `pty_relay.go`, and the two handlers). This section is the
authoritative wire contract the Android agent must implement; it describes what
the server already does, not an aspiration.

**Transport.** PTY traffic uses **WebSocket binary frames** on two sockets:

| Socket | Endpoint | Auth |
|:-------|:---------|:-----|
| Device | `GET /v1/device/:imei/stream` (already the device WS) | HMAC-signed handshake |
| Operator (browser) | `GET /v1/device/:imei/terminal` | session cookie + `shell.execute` scope |

The device does **not** open a second connection. PTY frames share the existing
device stream with telemetry and command results; the opcode distinguishes them.

**Frame layout** (big-endian, both directions):

```
[1B channel][1B type][2B length][payload]
```

| Field | Value |
|:------|:------|
| `channel` | `0x01` = PTY. Any other value is dropped. |
| `type` | see the table below |
| `length` | payload length, **max 65536** (`MaxPTYPayload`). The 2-byte field is the
allocation bound: the server rejects a frame whose declared length exceeds it
*before* allocating. |

| `type` | Name | Direction | Payload |
|:-------|:-----|:----------|:--------|
| `0x01` | `DATA` | both | session-prefixed (below) |
| `0x02` | `ACK` | device → operator | (ignored today) |
| `0x03` | `OPEN` | operator → device | session-prefixed, then `[2B rows][2B cols]` |
| `0x04` | `CLOSE` | both | session-prefixed |
| `0x05` | `RESIZE` | operator → device | session-prefixed, then `[2B rows][2B cols]` |
| `0x06` | `ERROR` | both | session-prefixed, then free text |

**Session-prefixed payloads.** A device multiplexes several sessions over one
socket, so any frame carrying session data names the session:

```
[1B idLen][idLen bytes session id][data...]
```

`idLen` is `1..64`. A UUID is 36 bytes. A malformed header drops the frame.

**Operator control channel.** The browser's control messages are JSON *text*
frames (the browser distinguishes text from binary by the opcode):

| Type | Direction | Fields |
|:-----|:----------|:-------|
| `pty_open` | operator → server | `sessionId` |
| `pty_opened` | server → operator | `sessionId` |
| `pty_resize` | operator → server | `sessionId`, `rows`, `cols` |
| `pty_ack` | operator → server | `sessionId`, `credits` |
| `pty_close` | operator → server | `sessionId` |
| `pty_closed` | server → operator | `sessionId`, `reason` |
| `pty_error` | server → operator | `sessionId`, `error` |

**Session lifecycle.**

1. The browser sends `pty_open` with a client-generated `sessionId`.
2. The server registers the session (refusing past the per-device cap) and sends
   `OPEN` to the device with the initial window size (24×80 until the browser
   reports otherwise).
3. The device allocates the PTY and streams `DATA` frames. The server relays
   them to the operator, applying the credit window.
4. `RESIZE` is forwarded when the browser reports a size change; the device
   applies `TIOCSWINSZ`.
5. Either side may close. The server also closes on: operator disconnect, device
   disconnect, or idle TTL.

**Flow control (credit window).** The operator starts with **64 credits**
(`DefaultPTYCreditWindow`). Each `DATA` frame delivered consumes one. When the
window is exhausted the server **drops** device output rather than buffering it,
so a `cat` of a large file cannot grow server memory. The browser returns credit
with `pty_ack`; the server caps the total at the window, so a buggy or hostile
operator cannot inflate it. **The browser must ack, or output will be dropped.**

**Limits (server-enforced).**

| Limit | Value | Purpose |
|:------|:------|:--------|
| `MaxPTYPayload` | 64 KiB | bounds a single frame's allocation |
| per-device sessions | 4 | one operator cannot open unbounded terminals |
| credit window | 64 frames | backpressure against a fast producer |
| session TTL | 2 h idle | a forgotten tab cannot hold a device open |
| operator outbox | 256 frames, 5 s wait | a slow browser cannot grow server memory |

**What the device must do.**

- Decode the frame header; reject `length > 65536`.
- Parse the session-prefixed payload; keep a `sessionId → pty fd` map.
- On `OPEN`: allocate the PTY, apply the initial size, start reading.
- On `DATA` (from the operator): write to the PTY master.
- On `RESIZE`: `TIOCSWINSZ` on the master.
- On `CLOSE`: `SIGHUP`/reap the child, drop the mapping.
- Emit `DATA` with the session prefix for all PTY output.
- Send `CLOSE` when the shell exits on its own.

**Not yet implemented on the server** (see the server-side status table in §13): the
`shell.execute` scope is not in any default role set, session expiry is not yet
scheduled, and there is no PTY audit or recording. Treat those as follow-ups, not
as done.

- **`SIGWINCH`** on resize via `TIOCSWINSZ` on the master fd (device side).
- **`tmux` inside Debian** so a dropped WS reattaches instead of losing the session.


---

## 7. App runtime (supervisor)

**Does:** fork/exec, autostart, crash restart with backoff, `inotify` on `/home/apps`,
`http_proxy`, the `app-*` CLI.

1. **PID validation.** PIDs are reused across reboots. Store `pid` **plus a start-time
   token** (`/proc/<pid>/stat` field 22, `starttime`); a mismatch means "not our process".
2. **Bind address is explicit, and LAN exposure is authenticated.**
   - Single-phone / cloud-only: bind **`127.0.0.1`**.
   - Hotspot sharing: bind **`0.0.0.0`** *and require a token* — anyone on the hotspot
     could otherwise reach a flasher unauthenticated.
   - `0.0.0.0` binds every interface; it does not mean localhost.
3. **`app-expose` is a real project.** HTTP-over-WS tunneling (streaming, chunked, large
   bodies, timeouts) + wildcard DNS + wildcard TLS + org-scoped auth at the proxy. Scope
   it as "cloudflared-lite" and build it last.

---

## 8. Lifecycle, boot, survival

```
BOOT_COMPLETED -> BootReceiver -> GatewayService.startForeground(specialUse)
                                   ├─ acquire wake lock
                                   ├─ start android.sock
                                   ├─ start PTY / USB brokers
                                   ├─ launch proot (Layer 2) + pty-agent + supervisor
                                   ├─ connect cloud WS (HMAC)
                                   └─ start watchdog (restart engine with backoff)
```

Onboarding must include, explicitly:

1. Open the app once (many OEMs block autostart until first launch).
2. Grant battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` plus
   vendor-specific prompts).
3. Sideload acknowledgment (Unknown sources flow, older-Android warning).

FCM wakes the FGS when offline; the device then reconnects and drains pending commands.

---

## 9. Security and privilege model

1. **Capabilities are the grant.** A PTY or USB session exists only because Layer 1
   created the fd. Closing it revokes access. No ambient device access.
2. **Per-session bridge tokens.** `android.sock` accepts a connection only with a session
   token issued by Layer 1 at session creation.
   - Token = 128-bit random, bound to `{sessionId, ptyId?, usbFd?, apiAllowlist, expiry}`.
   - Passed to the Debian process as an **environment variable at exec** — not on disk,
     not on the command line.
   - `ApiRouter` validates scope before dispatch: a session scoped to `usb` calling
     `camera` is denied and audited.
   - Revoked, and fds closed, when the session ends.
3. **Constrained shell by default.** Allowlisted binaries and a restricted `PATH`;
   arbitrary shell is a gated break-glass mode.
4. **Everything is audited.** `PTY_SESSION_OPEN` and `USB_ATTACH` are critical tier →
   MFA + confirmation, matching the existing `device.factory_reset` pattern.
5. **PTY recording is opt-in.**
   - Default: off. Nothing recorded, nothing leaves the device.
   - Enable per-device (operator) or per-session.
   - Format: asciinema `.cast` (plain text, greppable, replayable) at
     `files/recordings/<sessionId>.cast`.
   - Retention: max size (~50 MB) and max age (~7 days), then rotate.
   - A banner is shown in the terminal whenever recording is active — never record
     silently.
   - Upload only if the operator opts in; otherwise it stays local for forensics.
6. **No secrets in URLs.** HMAC-signed requests only.

---

## 10. Honest scaling rules

"120+ APIs, no refactor" is true **inside the Android app**, false at the server.

| Change | Android work | Server work |
|:-------|:-------------|:------------|
| New Android API | `NewApi.kt` + 1 router line + `apis.txt` | none |
| New command type | dispatcher case | `risk_catalog.go` entry + handler |
| App proxy | `AppProxyRelay.kt` | **new routes + wildcard TLS/DNS** |
| PTY relay | frame types | **new WS relay** |
| New device | edit `config.json` only | none |

The "no refactor" property is a property of the Android app, not the system.

---

## 11. Mobile-only modes

1. **Same phone, no network.** `app-start flasher --bind 127.0.0.1` → Chrome →
   `http://localhost:3000`. Zero network.
2. **Hotspot, no internet.** Phone A hotspot `192.168.43.1`; app bound `0.0.0.0` **with
   token auth**; Phone B → `http://192.168.43.1:3000?t=…`.
3. **With internet.** Cloud URL `https://api.vyzorix.com/d/<device>/app/<name>/` via the
   reverse proxy. If Phone B is on A's hotspot it gets both.

---

## 12. Build order (de-risk first)

Two fd-capabilities decide whether the premise holds. **Build these first.**

1. **Spike: PTY fd into proot.** Allocate PTY in NDK, `setsid` + `TIOCSCTTY`, exec proot
   → bash, drive I/O from Layer 1. If this works, the terminal pillar is proven.
2. **Spike: USB fd via libusb.** `UsbManager` → `getFileDescriptor()` →
   `wrap_sys_device` → a control transfer. If this works, the USB pillar is proven.
3. **Layer 1:** `android.sock` + `ApiRouter` + first 5 APIs + proot launch + watchdog.
4. **Layer 4:** cloud WS against the real `/v1/device/:imei/stream` with HMAC;
   `SHELL_EXEC` end to end (Phase 0 — ships value without PTY). The device agent
   implements the executor; the server only classifies and dispatches it (see
   §5).
5. **Layer 2:** `pty-agent` multiplexer, binary framing, `tmux`.
6. **Layer 3:** supervisor + `app-*` + inotify + PID start-time tokens.
7. **Last:** public app proxy (wildcard DNS/TLS, streaming).

---

## 12.1 Deferred, and why (recorded so it is not re-litigated)

| Item | Layer | Deferred because |
|:-----|:------|:-----------------|
| `android-bin` generator (`apis.txt`, `generator.go`, one binary + 120 symlinks) | 2 | It is Layer 2 work: the generator writes into the Debian userspace, which does not exist until the rootfs ships. The device side only needs the socket contract, which is already built. |
| `app_process` supervisor spawn | 3 | Layer 3 sits on Layer 2. Spawning a supervisor before there is a userspace to supervise inverts the build order. |
| Phantom-process-killer mitigation | 1 | Not a targetSdk-gated behaviour, and there is no `ActivityManager` API for it — `setPhantomProcessList` does not exist. The real lever (`settings_enable_monitor_phantom_procs`) needs shell/root or Shizuku. See the note on `VyzorixForegroundService`. |
| Shizuku execution backend | — | Documented as post-v1.0. The `ExecutionBackend` interface is the insurance policy; only `proot` is implemented. |
| USB pillar | 1 | Moved to last by decision: it needs real hardware to verify, and everything else can be proven on the emulator. |

---

## 13. Change log vs v2

| # | v2 claim | v3 fix | Why |
|:--|:---------|:-------|:----|
| 1 | targetSdk unstated | **28, hard constraint; sideload-only** | Android 10 exec restriction; SELinux domain mapping; Termux precedent |
| 2 | `/data/local/debian`, `/data/local/vyzorix-apps` | **`files/` (app-private) + optional `/sdcard/Android/data/...`** | `/data/local` is root-owned `0751` |
| 3 | `wss://…/ws/device?token=jwt` | **`/v1/device/:imei/stream`, HMAC-signed** | Actual server; no JWT in URL |
| 4 | Kotlin WS **and** pty-agent WS | **Layer 1 only; pty-agent is local** | Single-owner invariant |
| 5 | PTY allocated inside proot | **PTY allocated in Layer 1, fd passed in** | `/dev/ptmx` not guaranteed under proot |
| 6 | Immutable base **and** apt on device | **Read-only base + writable overlay prefix** | No mount → no overlayfs; updates would wipe installs |
| 7 | `0.0.0.0` "binds to 127.0.0.1" | **Explicit bind + auth for LAN** | `0.0.0.0` = all interfaces |
| 8 | `{"type":"pty_output"}` per chunk | **Binary frames + flow control + SIGWINCH + tmux** | Overhead, binary safety, session survival |
| 9 | "Each layer testable alone" | **Testable against a mock of the layer below** | Layer 2 genuinely needs Layer 1 |
| 10 | "3-layer onion" with 2.5 | **4 layers, renumbered** | "2.5" was a smell |
| 11 | `/tmp/android.sock` vs `files/android.sock` | **One path: `files/android.sock`** | Consistency |
| 12 | `{pid}` in `apps.json` | **`pid` + start-time token** | PID reuse |
| 13 | Phantom killer = main risk | **OEM killer = main risk; phantom reaps orphans** | AOSP `PhantomProcessList` |
| 14 | No boot/FGS detail | **`specialUse` + battery exemption + OEM steps** | Android 12/14 FGS rules |
| 15 | No DNS/resolv.conf | **Bind `resolv.conf`** | No DNS otherwise |
| 16 | 120 APIs "no refactor" | **Honest table (§10)** | Server work is real |

### Decisions taken in the v3 review (previously open questions)

| Question | Decision |
|:---------|:---------|
| Overlay location | **App-private `files/overlay/`.** Shared storage is `noexec` and slower, so binaries cannot live there. `/sdcard` is an optional export target only. No SD card ever required. |
| Shizuku backend | **Documented as future work**, added post-v1.0 stability. Define the `ExecutionBackend` interface now; implement `proot` only. |
| Bridge token model | **Per-session tokens** (§9.2). |
| PTY recording | **Opt-in**, off by default, asciinema `.cast`, on-screen banner when active. |
| targetSdk | **Pinned at 28** as a platform constraint. Escape hatch is the root backend; the `ExecutionBackend` abstraction is the insurance policy. |

### Server-side status (what is actually built)

The server half of this design is implemented in `vyzorix-update-server`. This is a
snapshot of reality, so the Android work has a fixed target.

| Area | Status | Notes |
|:-----|:-------|:------|
| Inbound device frame routing | **Done** | `internal/ws/inbound.go`. ReadPump switches on the opcode; binary frames go to the feature handler. Dispatch is panic-guarded (an unrecovered panic on the ReadPump goroutine would kill the process). Binary frames are bounded to header + 64 KiB before dispatch. |
| Command results | **Done** | `command_result` text frames, plus an HTTP fallback at `POST /v1/device/:imei/command/:dispatchId/result`. Both converge on one guarded service call; ownership is checked so a device cannot complete another device's command. |
| Command result storage | **Done** | Migration 73 adds `commands.result`; `result` and `failure_reason` are kept distinct. |
| PTY frame codec | **Done** | `internal/ws/pty_frame.go`, with tests. |
| PTY session registry | **Done** | `internal/ws/pty_registry.go`: per-device cap, credit window, TTL. |
| PTY relay | **Done** | `internal/ws/pty_relay.go`: input, output, control, teardown. |
| Ephemeral device send | **Done** | `Hub.SendEphemeral` drops rather than persisting, so keystrokes never hit the database. |
| Operator terminal endpoint | **Done** | `GET /v1/device/:imei/terminal`, session auth + org check. |
| `shell.execute` scope | **Done** | Granted to `admin` and `super_admin` only; deliberately absent from `viewer` and `operator`. `usb.attach` is admin-only too; `apps.read` is viewer, `apps.write` is operator. Covered by `defaults_test.go`. |
| Session expiry scheduling | **Done** | `pty_expiry_worker.go` closes idle sessions on a ticker and prunes old casts, under the server lock. Registered on the lifecycle manager. |
| PTY audit | **Done** | `ActionPTYSessionOpened`/`Closed`; the operator handler records open, close and denial with the reason and recording flag. |
| PTY recording | **Done** | asciinema v2 casts via `pty_recorder.go`, opt-in per session, size-capped, swept by age. Output only, so prompt input is never captured. |
| End-to-end test | **Done** | Real operator and device WebSockets through the hub, relay and both handlers: round trip, teardown on disconnect, and cast output. |
| Terminal risk gate | **Done** | `PTYAuthorizer` delegates to the shared command `Authorizer`, so `PTY_SESSION_OPEN`'s critical tier is enforced: MFA-verified session plus a single-use confirmation token (449 when satisfiable, 403 on hard deny). This *is* the per-session MFA the earlier revision deferred. |
| Rate limiting | **Done** | `TerminalOpenLimit`, 20/min, applied to both the terminal and recording routes. |
| Recording retrieval | **Done** | `GET /v1/dashboard/device/:imei/recordings/:sessionId`, org-scoped, session ID constrained to a UUID with a resolved-path check. |
| Race-free under `-race` | **Done** | Two `Client` field races (on `log` and `OrgID`) found and fixed. |
| `SHELL_EXEC` / `USB_ATTACH` / `APP_*` dispatch | **Server side done, device side pending** | The server classifies and delivers these; the device agent implements the executors. No server-side handler is needed or wanted — see §5. |
| **USB / app scopes are not yet enforced** | **Open** | `ActionUsb`, `ActionAppsRead` and `ActionAppsWrite` exist, are granted by role, and have action sets — but **no route checks them**, because the USB and app endpoints do not exist yet. They become enforced when those routes are built. Until then they are policy with nothing to apply to. |
| App proxy | **Not started** | §10's cloudflared-lite work. |

The terminal feature is built, race-free and covered by tests end to end. What remains
is **new surface, not missing pieces**: the USB and app routes (which will consume the
scopes already defined), and the public app proxy.

---

## 14. Open questions

1. **Root backend timing** — implement alongside v1.0 as an optional path, or only when a
   future Android forces it?
2. **`app-expose` scope** — full wildcard-DNS/TLS proxy in v1, or defer to v1.1 and ship
   with the hotspot + cloud-relay modes only?
3. **Recording upload** — if the operator opts in, does the recording stream to the
   server live, or upload on session end?
4. **`targetSdk 28` exit strategy** — what is the trigger to switch to the root backend,
   and how do we detect it on a device that cannot exec?
