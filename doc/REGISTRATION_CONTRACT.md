# Registration contract — how a device joins an account

**Status:** design, agreed in outline. Supersedes the inbox/confirm flow.
**Parent:** `doc/ANDROID_SERVICE_ARCHITECTURE.md` §5 (cloud connector) and §9 (security).
**Related:** `vyzorix-update-server/documents/ANDROID_SERVICE_BOUNDARY_CONTRACT.md` (what a session may do, once registered — it lives in the server repo).

This defines one thing: **how a freshly installed device becomes a known, owned,
configurable device in exactly one account.** It is not about what the device may
then do — that is the boundary contract.

---

## 1. Why the existing flow is replaced

The shipped flow is: device POSTs to `/v1/device/inbox`, an operator approves, the
server mints a 32-byte `command_secret`, **pushes that secret to the device over
FCM**, and the device POSTs it back to `/v1/device/confirm`. Three defects:

1. **The root secret travels over an unauthenticated channel.** `inbox_service.go`
   puts the plaintext secret into an FCM push. The architecture states FCM is "an
   unauthenticated wake channel, not a command-authority channel" — yet the
   command-signing key is delivered over it. Everything downstream is only as
   strong as that handoff.
2. **`/confirm` proves nothing.** The device compares a secret it just received
   with the one the server sent. That is a receipt, not authentication: anyone who
   intercepted the push can confirm.
3. **The two secret paths disagree.** The inbox flow stores `password.HashSecret`,
   the confirm path compares `sha256.Sum256`. Different hashes for the same secret,
   so confirm can never match. The path is dead code that looks live.

There is also a fourth problem the flow never solved: **a fresh device has no way
to know which account it belongs to.** `/v1/device/inbox` is public, so today any
caller can create inbox entries for anyone.

---

## 2. The one idea

**The device proves who it is with a keypair it generates itself; the pairing code
is only a rendezvous; the config carries settings and never a secret.**

Three consequences, and they are the whole point:

- **No secret is ever transmitted.** The private key is generated on the device and
  never leaves it. Nothing to intercept, on FCM or anywhere else.
- **The APK is generic.** One artifact, one release pipeline. Nothing
  user-specific is compiled in, so a downloaded APK is worth nothing on its own.
- **The server stays authoritative.** The device reports facts; the operator
  decides ownership and policy; the server assembles and signs the config.

---

## 3. The flow

### Phase A — the device comes up, with no identity

1. First launch. The service starts. There is no UI.
2. The device generates an **EC P-256 keypair** in the Android Keystore, once. The
   private key is non-exportable and never leaves the device.
3. The device collects its facts: a **device-generated UUID** (the stable ID),
   model, manufacturer, OS version, API level, ABIs, storage, RAM, and a **tier
   hint** (unrooted / rooted / advanced).
4. The device POSTs `{deviceUuid, publicKey, deviceInfo}` to `POST /v1/device/enroll`
   — public, because there is nothing to authenticate *with* yet. Unlike the current
   `/v1/device/inbox`, which is public and **unmetered**, this endpoint must be
   rate-limited per IP from the start: an unauthenticated endpoint that writes a row
   is a flood target.
5. The server creates a **pending** device record and returns a **pairing code**,
   its expiry, and a poll interval.
6. The device posts a **notification** (a system surface, not app UI):

   > Vyzorix setup — code `K7QM-3X2P`
   > Enter it at vyzorix.com/pair

### Phase B — the user claims it

7. The user, already signed in on the web app, opens `/pair` and types the code.
8. The server looks it up: code valid, not expired, not already claimed.
9. The server shows the device's facts — *"Pixel 7a · Android 13 · 4 GB · added 40
   seconds ago"* — and the user confirms it is theirs, picks the organization, a
   name, and the policy.
10. The server binds the device: owner, org, name, tier cap. It consumes the code
    and transitions **pending → approved**.
11. The server assembles `vyzorix.v` (the config) and signs it.

### Phase C — the device receives the config

12. The device is holding its WebSocket open through onboarding, so the config
    arrives as a `device_config` frame the moment it is approved. No push needed.
13. The device verifies the server signature, applies the config, stores it, and
    transitions to **registered**.
14. The device reports `device_ready`.
15. The notification updates to *"Vyzorix is running."*

If the device went offline between A and B, it re-fetches its own state on
reconnect (see §7). Nothing is lost.

---

## 4. The pairing code

Server-issued, because the server owns uniqueness and entropy.

| Property | Value | Why |
|:--|:--|:--|
| Alphabet | Crockford base32 (`0-9A-Z` minus `I L O U`) | No ambiguous characters; a human reads it off a notification and types it |
| Length | 8 characters (grouped `4-4`) | ~40 bits. With the TTL and rate limit below, guessing a live code is infeasible |
| TTL | 10 minutes | Short enough that a leaked code is stale, long enough to walk to a browser |
| Use | single | A claimed code is consumed; replay fails |
| Rate limit | per IP and per account | Brute force is bounded, not merely discouraged |

The threat this closes: an attacker who guesses a live code claims *someone else's*
device into *their* account, and can then command it. Entropy alone is not the
defence — TTL, single-use and rate limiting are what make it adequate.

---

## 5. Identity and credentials

**What the device holds:**

- An EC P-256 private key in the Keystore. Non-exportable. Generated once, on first
  run, before any network call.
- The server's public signing key, pinned in the app. Used to verify config and
  command signatures.

**What the server holds:**

- The device's public key, registered at enroll.
- The device record: uuid, owner, org, name, tier cap, config version.

**What travels:**

- The public key, once, at enroll.
- The config, signed by the server.
- Nothing else. No shared secret, at any point, in either direction.

**Device → server** is authenticated by signing the request with the device's
private key; the server verifies against the registered public key.
**Server → device** is authenticated by the server's signature, verified against
the pinned public key.

> **This retires the HMAC `command_secret`.** The architecture says "reuse the HMAC
> scheme, do not invent a second signing scheme", and that instruction was written
> before the secret-delivery hole was visible. A shared secret that must be
> transmitted is the weaker design; a keypair that never moves is the stronger one.
> If keeping HMAC is preferred, the fallback is to envelope-encrypt the secret to
> the device's public key and deliver it over the WebSocket — the secret then never
> travels in the clear, at the cost of one extra step. Both are in §9.

---

## 6. The config (`vyzorix.v`)

Settings only. **A config must never contain a key, a token, or a password** —
otherwise the delivery channel inherits the problem this design removes.

```json
{
  "version": 1,
  "deviceId": "d_01H...",
  "organizationId": "org_01H...",
  "name": "field-ops-07",
  "tierCap": 2,
  "telemetryIntervalSeconds": 60,
  "features": { "terminal": true, "usb": false, "apps": false },
  "endpoints": { "stream": "/v1/device/:imei/stream" }
}
```

Delivered over the WebSocket the device already holds. Signed. Versioned, so a
device that missed a change re-fetches when the version it holds differs from the
one the server reports.

`tierCap` is the **server's cap**, intersected with the device's self-reported tier
at session open (boundary contract §3). A device that overstates its tier gets an
allowlist it cannot serve — harmless, because the fd is the real capability — but
the server never *lowers* the cap on a self-report.

---

## 7. State machine

Reuses the existing lifecycle, which has exactly three states —
`pending`, `registered`, `deregistered` (`device_lifecycle.go`). There is no
`approved` state and none is added: claiming a code and delivering the config are
both the single `pending → registered` transition, so "claimed but not yet
configured" does not need to be representable.

```
 (no record)
     │  POST /v1/device/enroll
     ▼
  PENDING ────────── code expires ──────────► (record expires, device re-enrolls)
     │  operator claims the code, binds owner + org, config is delivered
     ▼
 REGISTERED ──────── device offline mid-claim ─► re-fetches config on reconnect
     │
     │  DELETE /v1/device/:id
     ▼
DEREGISTERED (irreversible; 30-day retention)
```

A device that never gets claimed expires and re-enrolls with a fresh code. Nothing
is orphaned and nothing is ambiguous.

---

## 8. What is removed

- **FCM from onboarding.** The device holds its WebSocket open; the config arrives
  on approval. FCM's only real job is waking an *offline* device, which is a later
  concern. Dropping it here removes Firebase from the build entirely.
- **`POST /v1/device/confirm`.** Nothing to confirm: the device proved possession by
  generating the keypair it registered.
- **The FCM secret push.** The secret it carried no longer exists.
- **The dead `RegisterDevice` route and its two hashing paths.** One flow, one hash.
- **Accessibility as a registration gate.** It needs a trip to system Settings and
  is a large onboarding drop-off. It becomes a capability requested when something
  needs it, and the device degrades gracefully without it.

---

## 9. Honest limits and the one decision needed

- **Tier is a self-report.** The server cannot verify root. It is used to *cap*
  what is granted, never to grant more than the policy allows. The real control is
  the capability (the fd), per boundary contract §9.1.
- **IMEI is not the identity.** On Android 10+ `getImei()` needs
  `READ_PRIVILEGED_PHONE_STATE`, which is system-only; a normal app gets null or an
  exception, on any device. IMEI is collected as a best-effort *attribute*
  and may be absent. The stable ID is the device-generated UUID.
- **The pairing code is the only unauthenticated step**, by necessity — the device
  has no credential yet. Its safety rests on entropy + TTL + single-use + rate
  limiting, and it is worth stating that plainly rather than implying the step is
  authenticated.
- **Decision needed:** retire HMAC for the keypair (§5), or keep HMAC with
  envelope-encrypted delivery? The first is cleaner; the second is a smaller server
  change. Everything else here is independent of that choice.

---

## 10. Definition of done

1. A fresh install generates a keypair before any network call.
2. Enroll creates a pending device and returns a code; the device shows it in a
   notification, with no app UI.
3. An expired or already-claimed code is refused, and rate limiting bounds guessing.
4. Claiming binds owner and org, and consumes the code.
5. The config arrives over the held WebSocket, is signature-verified, and is applied.
6. A config containing a secret is refused by a test — the invariant, not a promise.
7. A device that missed its config re-fetches on reconnect by version.
8. `./gradlew gate` passes; the server gate passes; the flow is exercised end to end
   against the real server on the emulator.

---

## 11. Inventory — what gets written, and how much

Counts are estimates, sized so the work is plannable. Existing files are modified in
place; the repo's rule is that a new file must earn its name.

### 11.1 Device (Android repo)

**New packages: two.** `com.vyzorix.cloud` already exists as an empty placeholder and
becomes the client; `com.vyzorix.enroll` is new and holds only the pairing code and
its notification.

| File | ~LOC | Purpose |
|:--|--:|:--|
| `cloud/DeviceIdentity.kt` | 90 | Keystore EC P-256 keypair: generate once, load, sign. The only place the private key is touched. |
| `cloud/DeviceFacts.kt` | 110 | Collects uuid, model, manufacturer, OS, API level, ABIs, storage, RAM, tier hint. IMEI is best-effort and may be null. |
| `cloud/EnrollmentClient.kt` | 130 | `POST /v1/device/enroll`, parse the code + expiry + poll interval. |
| `cloud/ConfigStore.kt` | 80 | Persist and version `vyzorix.v`; refuse a config that carries a secret. |
| `cloud/ConfigVerifier.kt` | 70 | Verify the server's signature against the pinned public key. |
| `cloud/DeviceWsClient.kt` | 260 | The single WebSocket owner: connect, sign the handshake, heartbeat, reconnect with backoff, dispatch frames. |
| `cloud/CommandDispatcher.kt` | 150 | Verify a command's signature, route to an executor, report the result. Unknown type ⇒ report failed, never drop. |
| `cloud/ShellExecExecutor.kt` | 110 | The one command type for this build: run it through the existing PTY/bridge path, capture output. |
| `enroll/PairingCode.kt` | 60 | The code model: format, display grouping, expiry. |
| `enroll/PairingNotification.kt` | 90 | Post and update the notification. No Activity, no UI. |
| `bridge/apis/IdentityApi.kt` | 60 | Expose the device's own public key + uuid over the bridge, so the container can prove identity later. |

**Modified, not new:** `VyzorixForegroundService.kt` (own the cloud client, start
enrollment on first boot), `ProotLauncher`/`AppRouter` wiring for `IdentityApi`,
`libs.versions.toml` + `android-core/build.gradle.kts` (one dependency: an HTTP
client), `app/src/main/AndroidManifest.xml` (`POST_NOTIFICATIONS` is already
declared; add the enrollment service wiring).

**Dependency added: exactly one.** An HTTP client for the enroll call. The WebSocket
is hand-rolled on `java.net.Socket`/TLS or a client that also gives WS — the decision
is whether to pull OkHttp. It is the only new third-party dependency, and it is
worth it: hand-rolling TLS + WS + framing is where bugs live.

**Tests: ~8 new files, ~40 cases.**
- `DeviceIdentityTest` — key is stable across loads, signature verifies, key is not
  exportable.
- `ConfigStoreTest` — a config with a secret is refused; version mismatch triggers a
  re-fetch.
- `ConfigVerifierTest` — a bad signature is refused; a tampered field fails.
- `EnrollmentClientTest` — request shape, code parsing, error paths (MockWebServer).
- `CommandDispatcherTest` — valid signature routes; bad signature is refused; unknown
  type reports failed.
- `PairingCodeTest` — format, grouping, expiry boundaries.
- `HandshakeTest` — the canonical string matches the server's, byte for byte. **This
  is the spike** (§12) and it needs no device.
- Instrumented: `EnrollmentE2ETest` on the emulator against the real server.

### 11.2 Server (Go)

**No new packages.** Everything lands in existing ones, which is the point — the
repo's shape already has a place for each piece.

| File | Change |
|:--|:--|
| `api/handlers/inbox/` (or a new `enroll` handler in it) | `POST /v1/device/enroll`; `POST /v1/device/pair` (claim). ~200 LOC. |
| `api/server_routes.go` | Two routes; rate limiting on both. |
| `domain/device/device_entity.go` | `PublicKey` field; drop the HMAC secret if §5 retires it. |
| `infrastructure/storage/` | Migration 75: `devices.public_key`, `pairing_code`, `pairing_expires_at`. One file, following the existing migration pattern. |
| `application/device/device_service.go` | Retire `ConfirmDevice`; add `Enroll` and `Claim`. Delete the dead `RegisterDevice` hash path. |
| `application/inbox/inbox_service.go` | Remove the FCM secret push. |
| `api/handlers/command/command_execute.go` | Sign with the server key instead of the per-device secret (if §5 retires HMAC). |
| `infrastructure/crypto/` | Server signing keypair; verify device signatures. |

**Tests: ~6 new, ~25 cases** — enroll creates pending + returns a code; a claimed
code is refused; an expired code is refused; rate limit trips; claim binds owner and
org; the config never contains a secret.

### 11.3 Rough size

- **Device:** ~1,100 LOC production, ~700 LOC tests, 11 new files, 1 dependency.
- **Server:** ~450 LOC production, ~350 LOC tests, 0 new packages, 1 migration.
- **Sequencing:** the handshake spike first (§12), then enroll/claim on the server,
  then the device client, then the config delivery, then the exec path.

### 11.4 What is deliberately *not* here

- **FCM** — not needed for onboarding (§8). Its own slice, later, for waking offline
  devices.
- **USB** — deferred by decision.
- **Layer 2/3** — blocked on the rootfs, which is not in either repo.
- **App-level UI** — the web page is the UI; the device shows a notification.

---

## 12. The spike, and why it is first

The thing that can change the design is **whether the server and device agree on the
signature, byte for byte.** That is pure crypto and needs no device and no emulator,
so it is the cheap de-risk — the same discipline as the PTY spike.

Two canonical forms must match exactly:

- **Device → server** (enroll, and later every signed request): a defined
  concatenation of method, path, timestamp, nonce and body hash, signed with the
  device's private key.
- **Server → device** (config, commands): the server's existing canonical string
  shape, signed with the server's key, verified against the pinned public key.

The spike is one JVM test that reproduces the server's canonical string and verifies
a signature the server produced, plus one Go test that verifies a signature the
device produced. If those pass, the rest is plumbing. If they do not, the design
changes — and that is exactly what a spike is for.

**Decision still open from §5:** retire HMAC for the keypair, or keep HMAC with the
secret envelope-encrypted to the device's public key. The spike settles the crypto
either way; the choice changes how much of `command_execute.go` is touched.
