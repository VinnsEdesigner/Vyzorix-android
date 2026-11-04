# Build 3 — device side of registration. Requirements.

**Status:** proposal, for discussion. Nothing here is built yet.
**Parent:** `doc/REGISTRATION_CONTRACT.md` (the design), `doc/ANDROID_SERVICE_ARCHITECTURE.md` §2/§5/§9.
**Server counterpart:** `vyzorix-update-server` @ `a1cc9edb`.

This lists what the device build needs: packages, files, dependencies, the exact
wire contracts it must match, and the tests. It ends with the decisions that need
a human answer before any code is written.

---

## 0. What exists, and what this build adds

> **Status update (2026-11-04): this build is done.** The cloud side below is
> implemented and verified — enroll, the signed device WebSocket, config verify +
> apply + re-fetch, `SHELL_EXEC`, telemetry, and the interactive terminal relay.
> It lives in the `cloud-connector` module (`com.vyzorix.connector`), not
> `com.vyzorix.cloud` inside `android-core`; §3.1's option A was chosen over the
> recommendation. Live end-to-end against a real server passes
> (`CloudEnrollmentE2ETest`). Read the rest of this document as the contract it
> implemented, not as pending work.

Built and on the emulator (Build 1/2): the foreground service, the filesystem
unix-socket bridge with per-session capability tokens, the PTY broker and reader,
the proot launcher, the permission registry, and `SessionApi`. 44 JVM tests, 26
instrumented.

Not built, and this build: **the cloud side.** The device cannot enrol, cannot
hold its WebSocket, cannot receive or verify a config, and cannot receive or
execute a command. There is no `com.vyzorix.cloud` code at all — the package
directory exists and is empty.

---

## 1. Blocking findings on the server (must be fixed first)

I found these while reading the server to pin the wire contracts. Each one blocks
the device build, and each is a server change, not a device change.

### 1.1 The config the server delivers cannot be verified by the format's own verifier — CONFIRMED

`enroll_handler.deliverConfig` signs the config with
`ServerSigner.SignCanonical(document)`, which is ECDSA over
`SHA-256(raw document text)`. The device's verifier (`libv` `VVerifier.verify`)
verifies over the **libv canonical string** — comments, blank lines and the
signature line excluded, tokens re-joined with single spaces. Those are different
byte strings, so the signature can never verify on the device.

Worse, `RenderConfig` never appends a `v sig ecdsa-p256 <base64>` line at all, so
`libv.Parse` reports `Signed=false`: there is no signature line for the device to
find in the first place.

I proved both with a throwaway test against the real code path:

```
delivered config:
    MEUCICmzUjvN5nGZ6KP8o81qiETlBdygumOimTeKZTBZPvEDAiEAoroIm2YxKIvOCdrBQkxXKSt2G8rPZfQiXyLdbFj/4B8=
libv says Signed=false
libv cannot verify the delivered config: document is not signed
```

The delivered "config" is the document followed by a bare base64 blob.

**Fix:** back `SignDocument` with a real `libv.Signer` built from the same
`SERVER_SIGNING_KEY`, so `RenderConfig` emits a proper signed document. The
existing `ConfigForDevice(ctx, sign SignDocument, ...)` seam already allows this;
only `deliverConfig` changes. The config test in `config_service_test.go` already
does this correctly with `libv.NewSignerFromPEM` — production just does not.

### 1.2 Device HTTP requests have no working authentication

`requireHMAC` verifies device HTTP requests against the old per-device HMAC secret
(`hmacVerifier.Secret` → `clientService.GetHmacKey(deviceID)`). That secret is
gone — this is the thing `a1cc9edb` removed. `VerifyDeviceSignature` (the keypair
verifier, `device_signing.go`) exists but has **no production caller**.

Net effect: in production, every device HTTP route — `GET
/v1/device/:imei/commands/pending`, `POST
/v1/device/:imei/command/:dispatchId/result`, `PATCH /v1/device/:imei/fcm-token`
— fails authentication. The device cannot use them.

**Fix:** point `requireHMAC` at the keypair path: resolve the device's registered
public key and call `VerifyDeviceSignature` over
`DeviceCanonicalString(method, path, deviceID, timestampMs, nonce, body)`, with
the timestamp window and nonce replay check the old path had. The canonical string
already exists on the server and is well-formed (length-prefixed, newline-separated).

### 1.3 There is no way to re-fetch the config

`REGISTRATION_CONTRACT.md` §7 and §10.7 require "a device that missed its config
re-fetches on reconnect by version". There is no route for that, and no WebSocket
message for it either: the config is pushed exactly once, in `Claim`. A device
that was offline at claim time never gets a config.

**Fix (pick one):** a `GET /v1/device/:imei/config` on the keypair-authenticated
device group; or a `device_config_request` frame on the device WebSocket that
re-runs `deliverConfig`. The first is simpler and testable with curl.

### 1.4 Minor: the connect handshake has no freshness check

`verifyDeviceConnect` checks the signature but never the `sig_timestamp` or a
nonce, so a captured handshake replays indefinitely. The old HMAC path had a 5
minute window and a nonce cache. Worth restoring while we are here.

---

## 2. Wire contracts the device must match, exactly

Everything below is read off the server at `a1cc9edb`. These are the spike
(`REGISTRATION_CONTRACT.md` §12): if the device reproduces them byte for byte, the
rest is plumbing.

### 2.1 Enroll — `POST /v1/device/enroll` (public, rate-limited, idempotent)

Request:

```json
{
  "deviceUuid":   "d_01H…",          // required, the stable id (device-generated)
  "publicKey":    "-----BEGIN PUBLIC KEY-----\n…",  // required, P-256 SubjectPublicKeyInfo PEM
  "model":        "C22",
  "manufacturer": "Nokia",
  "osVersion":    "13",
  "appVersion":   "0.1.0",
  "deviceClass":  "phone",
  "tierHint":     1
}
```

Response `201`:

```json
{
  "deviceId":         "d_01H…",
  "pairingCode":      "K7QM3X2P",
  "pairingDisplay":   "K7QM-3X2P",
  "expiresAt":        1767225600000,
  "expiresInSeconds": 600
}
```

Errors: `400` malformed/missing key, `409` already claimed (re-enroll refused —
this is the anti-hijack rule), `429` rate limited. Code TTL is 10 minutes.
Alphabet is Crockford base32 (`0-9A-Z` minus `I L O U`), 8 chars.

### 2.2 Device WebSocket — `GET /v1/device/:imei/stream`

Handshake, when `EnforceHMAC` or production (query params, because an upgrade
cannot set headers):

| Param | Value |
|:--|:--|
| `sig_timestamp` | timestamp the signature covers |
| `sig_nonce` | random nonce the signature covers |
| `sig_signature` | base64 ASN.1 DER ECDSA P-256 |

The device signs `ConnectCanonical(deviceID, timestamp, nonce)`:

```
connect|<deviceId>|<timestamp>|<nonce>
```

digest = SHA-256 of that UTF-8 string; signature = ECDSA P-256 over the digest,
base64-encoded DER. (The server does no freshness check today — see §1.4.)

**Server → device, text (JSON):** a `CommandFrame`

```json
{"type":"…","dispatchId":"…","command":"…","nonce":"…",
 "signature":"base64","args":{…},"timestamp":1767225600000}
```

The signature is over `CommandCanonical(frame, deviceID)`:

```
command|<dispatchId>|<deviceId>|<command>|<timestamp>|<nonce>|<args>
```

where `<args>` is the raw JSON of the frame's `args`, or `{}` when empty.

The config arrives as such a frame: `type = "device_config"`,
`command = "DEVICE_CONFIG"`, and

```json
"args": {"version": 1, "document": "<the whole vyzorix.v text>"}
```

`timestamp` is the envelope's `issuedAt`. The frame has its own signature over the
envelope, and the document carries its own signature inside it — the device checks
both.

**Server → device, binary:** PTY frames.

```
[1B channel=0x01][1B type][2B big-endian length][payload]
```

| type | name | payload |
|:--|:--|:--|
| `0x01` | DATA | `[1B idLen][idLen sessionID][bytes]` |
| `0x02` | ACK | credit count |
| `0x03` | OPEN | session id + 4B resize (`rows` BE, `cols BE`) |
| `0x04` | CLOSE | empty |
| `0x05` | RESIZE | session id + 4B resize |
| `0x06` | ERROR | detail |

Max payload 64 KiB; the 2-byte length is the bound. A DATA payload is always
session-prefixed because one socket multiplexes several sessions.

**Device → server, text:** `command_result`

```json
{"type":"command_result","dispatchId":"d1","success":true,"output":"…"}
```

`error` is accepted as an alias for the failure text when `output` is empty.

**Device → server, text:** `telemetry`

```json
{"type":"telemetry","timestamp":…,"deviceId":"…","uptime":…,
 "riskScore":…,"audioMode":…,"bufferLevel":…,"thermalTemp":…,"speakerOn":…}
```

**Device → server, binary:** PTY frames — DATA (session-prefixed), ACK, CLOSE,
ERROR.

### 2.3 Device → server HTTP signing (for §1.2 once it lands)

```
UPPER(method)\n
path\n
deviceId\n
timestampMs\n
nonce\n
base64(SHA-256(body))
```

signature = base64 ASN.1 DER ECDSA P-256 over SHA-256 of that string. Headers to
agree on with the server: the old path used `X-Vyzorix-Nonce` /
`X-Vyzorix-Timestamp` / `X-Vyzorix-Signature`; the new verifier takes them as
parameters, so reusing those names keeps one scheme.

### 2.4 The config document (`vyzorix.v`)

Rendered by `RenderConfig`, signed by the format's own signer:

```
' vyzorix.v - device configuration
v device d_01H…
vv org org_01H…
vv name field-ops-07
vv tier 2
vv cloud
vvv stream /v1/device/d_01H…/stream
vvv hb 60s
vvv backoff 1s>30s
vv features
vvv terminal true
vvv usb false
vvv apps false
v version 1
v sig ecdsa-p256 <base64>
```

The device verifies with `libv` against the pinned server public key. A config
must never contain a secret — the server enforces it (`ValidateConfigHasNoSecrets`)
and the device must re-check on apply, because the invariant is the point of the
design and a device that trusts it blindly is one bad deploy from holding a secret.

---

## 3. Module and package layout

Two things to settle, then the files.

### 3.1 Where the client lives — DECISION

The architecture doc (§3) puts the cloud connector in its own module
`cloud-connector/` (`com.vyzorix.connector`), and it is already wired into
`settings.gradle.kts` and depended on by `:app`. But the module has no `src/` at
all, while `android-core` has an empty `com/vyzorix/cloud/` directory waiting.
`REGISTRATION_CONTRACT.md` §11.1 says the client goes in `com.vyzorix.cloud`
inside `android-core`.

Two options:

- **A — build it in `cloud-connector`.** Honours the module layout, gives the
  cloud code its own gate and test target, and keeps `android-core` the only
  module that touches Android APIs (which it still would — OkHttp is not an
  Android API). But the client needs the PTY framing and the bridge, so it would
  depend on `android-core`, and the "one WS owner" invariant would be spread
  across two modules.
- **B — build it in `android-core` under `com.vyzorix.cloud`,** delete or
  repurpose `cloud-connector`. Matches the contract, keeps the WS owner next to
  the PTY broker it feeds, one test target. Costs the separate module.

My recommendation is **B**, with `cloud-connector` kept only for the
`Endpoints.kt` constants and the later `AppProxyRelay` — the thin pieces that have
no dependency on the core. The WS owner wants to be next to the broker.

### 3.2 How to consume `libv` — DECISION

`libv` is a separate repo (`VinnsEdesigner/libv`), Kotlin/JVM, package
`com.vyzorix.libv`, built with the Kotlin JVM plugin at toolchain 21. The device
needs `VParser`, `VVerifier` and `VParser.canonicalString`. Options:

- **A — vendor the two Kotlin files** (`VParser.kt`, `VVerifier.kt`, ~400 lines)
  into `android-core`. Zero build machinery, no version skew, but a copy that can
  drift from the source of truth.
- **B — consume as a Maven artifact.** Needs `libv` published (jitpack or a GitHub
  Packages repo) and a repo added to `dependencyResolutionManagement`. Clean, but
  adds a publish pipeline and a network dependency to the build.
- **C — git submodule + `includeBuild`.** `libv/kotlin` is already a standalone
  Gradle build, so a composite build works. Most correct, most moving parts.

My recommendation is **B** if you are willing to publish `libv` (it already has a
CI gate and is public), else **A** with a comment naming the source commit. What I
would not do is a third, hand-rolled verifier — two implementations of the
canonical string is exactly the bug the spec calls out.

---

## 4. Files to write

New package `com.vyzorix.cloud` (in `android-core`; see §3.1):

| File | ~LOC | Responsibility |
|:--|--:|:--|
| `DeviceIdentity.kt` | 100 | Keystore EC P-256 keypair. Generate once on first run, load, sign. The only place the private key is touched. Non-exportable; the public half is what enroll sends. |
| `DeviceFacts.kt` | 110 | Collect uuid, model, manufacturer, OS, API level, ABIs, storage, RAM, tier hint. IMEI best-effort and nullable (§9 of the contract: it needs a system-only permission). |
| `DeviceUuidStore.kt` | 40 | Persist the generated uuid (DataStore). It is the stable id; it must survive reinstall of the process, and the server treats a changed uuid as a new device. |
| `EnrollmentClient.kt` | 130 | `POST /v1/device/enroll`; parse code, display, expiry. OkHttp. |
| `EnrollmentState.kt` | 70 | Persist `{deviceId, pairingExpiresAt, claimed}`; drive the notification's lifecycle. |
| `ConfigVerifier.kt` | 70 | Verify a delivered `vyzorix.v` with `libv` against the pinned server public key. Reject an unsigned one. |
| `ConfigStore.kt` | 90 | Persist and version the config; refuse to store one that carries a secret; expose "version differs from the server's" for the re-fetch. |
| `PinnedServerKey.kt` | 40 | The pinned server public key, loaded from assets. One place, so it is obvious where the trust anchor is. |
| `DeviceWsClient.kt` | 260 | **The single WebSocket owner.** Connect, signed handshake, heartbeat, reconnect with backoff, dispatch text and binary frames. Nothing else dials the cloud. |
| `WsHandshake.kt` | 50 | Build `ConnectCanonical` and sign it. Separated so the byte-for-byte spike can test it with no socket. |
| `CloudFrameCodec.kt` | 90 | Encode/decode the text frames: `CommandFrame`, `command_result`, `telemetry`. kotlinx.serialization. |
| `PtyFrameCodec.kt` | 120 | Encode/decode the binary PTY frames (§2.2). Must match the server byte for byte — this is the other half of the spike. |
| `CommandDispatcher.kt` | 150 | Verify a command frame's signature, route to an executor, report the result. An unknown type reports **failed**, never dropped (§5 of the architecture). |
| `ShellExecExecutor.kt` | 110 | The one command type this build implements: run `SHELL_EXEC` through the existing PTY/bridge path, capture output, report. |
| `DeviceRequestSigner.kt` | 70 | Build the §2.3 canonical string and sign it. Used by the HTTP calls that are not the WebSocket. |
| `CloudConfig.kt` | 40 | Base URL, endpoints, backoff bounds. The `Endpoints.kt` from the architecture doc, in one place. |

New package `com.vyzorix.enroll`:

| File | ~LOC | Responsibility |
|:--|--:|:--|
| `PairingCode.kt` | 60 | The code model: format, display grouping, expiry countdown. |
| `PairingNotification.kt` | 100 | Post and update the notification. No Activity, no UI — the web page is the UI. |

New file in the existing bridge:

| File | ~LOC | Responsibility |
|:--|--:|:--|
| `bridge/apis/IdentityApi.kt` | 60 | Expose the device's own uuid + public key over `android.sock`, so the container can prove identity later. One `register` line in the service. |

Modified in place:

| File | Change |
|:--|:--|
| `service/VyzorixForegroundService.kt` | Own the `DeviceWsClient` and `EnrollmentState`; start enrollment on first boot; register `IdentityApi`; feed PTY sessions from cloud frames and push PTY output back. |
| `bridge/ApiRouter.kt` | No change needed (already `register`). |
| `gradle/libs.versions.toml` | Add OkHttp; add `libv` per §3.2. |
| `android-core/build.gradle.kts` | The new dependency; an `assets/` entry for the pinned key. |
| `android-core/src/main/AndroidManifest.xml` | Enrollment notification channel; `POST_NOTIFICATIONS` is on the app manifest already. |
| `app/src/main/AndroidManifest.xml` | Nothing new expected; verify after the merge. |
| `app/.../LaunchActivity.kt` | Request `POST_NOTIFICATIONS` on first launch (API 33+), then start the service. |

Dependency added: **one** third-party library, OkHttp 4.12.0, for HTTP and the
WebSocket. Hand-rolling TLS + WebSocket framing is where the bugs live, and the
architecture doc reaches the same conclusion. Everything else is Kotlin stdlib,
kotlinx-serialization, DataStore, and `libv` — all already in the catalog or
ours.

---

## 5. Tests

The repo's rule: tests live in `tests/android-core/{unit,instrumented}`, never in
the module tree. The dead-code gate is strict — every new public declaration needs
a caller or a line in `config/deadcode-public-api.txt`.

Unit (`tests/android-core/unit/kotlin/com/vyzorix/cloud/`):

| Test | Covers |
|:--|:--|
| `WsHandshakeTest` | `ConnectCanonical` matches the server's, byte for byte; a signature verifies against a server-produced fixture. **This is the spike** (§12). |
| `PtyFrameCodecTest` | Encode/decode round trips; a frame the server produced decodes; the 64 KiB bound and the session header are enforced. Cross-checked against fixtures generated from the Go side. |
| `CloudFrameCodecTest` | `CommandFrame` and `command_result` parse; an unknown `type` is not silently swallowed. |
| `DeviceRequestSignerTest` | The §2.3 canonical string matches the server's. |
| `ConfigVerifierTest` | A good config verifies; a tampered field fails; an unsigned one is refused. Uses the `libv` vectors. |
| `ConfigStoreTest` | A config carrying a secret is refused; a version mismatch is reported. |
| `CommandDispatcherTest` | Valid signature routes; bad signature refused; unknown type reports failed. |
| `EnrollmentClientTest` | Request shape, response parsing, error paths. MockWebServer. |
| `PairingCodeTest` | Format, grouping, expiry boundaries. |
| `DeviceIdentityTest` | Key is stable across loads; signature verifies; the key is not exportable (Keystore-backed, so this is a Robolectric/instrumented split). |

Instrumented (`tests/android-core/instrumented/kotlin/`):

| Test | Covers |
|:--|:--|
| `EnrollmentE2ETest` | Against the **real server** on the emulator: enroll → notification shows a code → an operator claims it → the config arrives over the held WebSocket → it verifies → it applies. This is DoD 1–5 and 8. |

The spike first, and it is a JVM test plus a Go test that need no device: the
device signs a connect and a request, the server verifies them; the server signs a
config and a command frame, the device verifies them. If that passes, the rest is
plumbing. If it does not, the design changes — which is what a spike is for.

---

## 6. Sequencing

0. **Server:** fix §1.1–§1.4. Nothing on the device can be tested end to end until
   the config verifies and device HTTP auth works.
1. **Spike** (§5): the two canonical forms and the two signatures, JVM + Go, no
   device.
2. Enrollment: `DeviceIdentity`, `DeviceFacts`, `EnrollmentClient`,
   `PairingCode`, `PairingNotification`. Visible on the emulator: a notification
   with a code.
3. WebSocket: `DeviceWsClient`, `WsHandshake`, heartbeat, backoff. Verifiable
   against the real server with a claimed device.
4. Config: `ConfigVerifier`, `ConfigStore`, apply on arrival, re-fetch on version
   mismatch.
5. Commands: `CommandDispatcher`, `ShellExecExecutor`, result reporting.
6. `IdentityApi` over the bridge.
7. Instrumented E2E on the emulator; `./gradlew gate`; the server gate.

---

## 7. Decisions needed before code

1. **Client location** — `cloud-connector` module, or `android-core/com/vyzorix.cloud`
   (§3.1). I recommend the latter.
2. **`libv` consumption** — vendor the files, a published artifact, or a composite
   build (§3.2). I recommend publishing it.
3. **Server fixes** — confirm §1.1–§1.4 are in scope for this build, or whether
   the device should instead reproduce the server's raw-text config
   canonicalisation (worse; it is the drift the spec warns about).
4. **Header names** for §1.2 — reuse `X-Vyzorix-*`, or a new set.
5. **Pinned key delivery** — an asset in the APK, or a `BuildConfig` field, and
   who rotates it.
6. **Scope of this build** — is `SHELL_EXEC` the only command type (as the
   contract says), and is the operator terminal relay in scope or deferred? The
   server already relays PTY over the device WebSocket, so the device's half is
   `PtyFrameCodec` + wiring, not new design.
