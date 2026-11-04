# Build 2 contract — the service host

> **Status: met, and superseded in one place.** This is the contract Layer 1 was
> built against; `BUILD_2_VERIFICATION.md` records the run that proved it. Read it as
> the contract that was implemented, not as pending work.
>
> Two rows in §1 below have since gone stale and are left as written because the
> document is a historical record:
>
> - *"Cloud WebSocket — Build 4. Needs the server's HMAC handshake."* The cloud
>   WebSocket **shipped** (Build 3 / Layer 4, in `cloud-connector`), and the
>   handshake is a **device keypair**, not HMAC — the HMAC secret travelled over FCM
>   and was retired (`REGISTRATION_CONTRACT.md` §5).
> - *"Full Debian rootfs packaging — Layer 2 work."* Still true, and now specified:
>   see `ANDROID_SERVICE_ARCHITECTURE.md` §6.1.1.
>
> The §5 definition of done is met for items 1–5 and 8; items 6–7 are proven down to
> the proot exec and are blocked on the rootfs asset, which is an asset gap, not a
> code gap. See `BUILD_2_VERIFICATION.md`.

Status: **agreed, pre-implementation** (historical — the build is met; see the banner above)
Supersedes: nothing. Build 1 (`capability/`) is unchanged except where noted.
Parent: `doc/ANDROID_SERVICE_ARCHITECTURE.md`, build order step 3.

Build 1 proved the PTY *syscalls* work. Build 2 is what turns that library into a
**running service with a door**: something alive, reachable, survivable, and
speakable-to by a process on the far side of proot.

One line: *a foreground service that stays alive, exposes a local socket, answers
API calls under per-session authorization, and launches proot.*

---

## 1. Scope

### In

- Foreground service that owns every layer and survives process death.
- Boot hook, watchdog.
- Local unix socket server (`android.sock`) with a framed protocol.
- Per-session capability tokens, validated once per connection.
- Five Android APIs behind the router.
- PTY reader loop, and the fd-ownership fix Build 1 left open.
- proot launcher: build the command line, exec it on the PTY slave.
- Reader pushes PTY output to the socket, so a shell can be driven end to end.

### Out (with reason)

| Deferred | Why |
|---|---|
| Cloud WebSocket | Build 4. Needs the server's HMAC handshake. |
| USB broker | Separate pillar, no interaction with PTY. |
| Full Debian rootfs packaging | Layer 2 work. Build 2 only needs proot to launch, provable with a trivial target. |
| Supervisor / app-runtime | Layer 3. |
| PTY recording, tmux, constrained shell | Later; noted in §9 as future work. |
| Permission-gated APIs (camera, location) | The first five are chosen to need no runtime permission. |

---

## 2. Design decisions

These are settled. Changing any of them later is expensive, which is why they are
recorded here rather than in a commit message.

### 2.1 fd ownership — the reader owns the master fd

Build 1 documented this and did not implement it: `close()` killed the child but
never closed the fd, and `releaseFd` had no production caller. The result was a
descriptor leak on the close path.

**Rule: exactly one thread reads a master fd, and that thread closes it.**

- `close()` sets the closed flag and kills the child. It does **not** close the fd.
- The reader loop polls, observes the flag, stops reading, and calls
  `releaseFd()` — which closes the fd and is the only place a master fd is closed.
- `close()` is therefore asynchronous with respect to the fd. Callers that need to
  know the fd is gone await `awaitClosed()`.

This is the only model that is provably race-free: closing an fd another thread is
blocked on does not fail cleanly — the number is released and can be handed to an
unrelated file, so the reader then reads from the wrong thing.

### 2.2 Socket namespace — filesystem

`LocalSocketAddress(name, Namespace.FILESYSTEM)`, bound at
`files/android.sock`.

Chosen over the abstract namespace for one reason that outweighs the rest: the
Debian side must reach it, and a **filesystem path is something proot can bind**
into the container's tree. An abstract name is invisible to the filesystem, so a
proot'd process could only reach it by name with no way to bind it, which puts the
whole handoff at the mercy of how proot handles abstract sockets.

Costs accepted: the socket file needs `0700` and a stale file survives a crash, so
startup unlinks any existing node before binding.

**This is not yet proven.** Risk 2 in §4 is exactly "can a proot'd process connect
to it". If that fails, the fallback is the abstract namespace plus an SCM_RIGHTS
handoff of the listening socket. The protocol does not change either way.

### 2.3 Protocol framing — length-prefixed binary

One frame shape for everything, so control and data share a transport:

```
[4B length][1B type][payload]
```

`length` is the payload length, big-endian, capped at 1 MiB. A frame longer than
the cap closes the connection: without a bound, a hostile client can make the
server allocate without limit.

| type | name | payload |
|---|---|---|
| `0x01` | request | JSON `{id, api, args}` |
| `0x02` | response | JSON `{id, ok, result \| error}` |
| `0x03` | pty_output | `[1B channel][bytes]` |
| `0x04` | pty_input | `[1B channel][bytes]` |
| `0x05` | pty_credit | JSON `{channel, bytes}` |
| `0x06` | event | JSON `{topic, data}` |

JSON for control because it is debuggable and the control path is not hot.
Binary for PTY because base64 and escaping on every keystroke is not acceptable,
and terminal output is not text-safe in general.

**Request ids** exist so responses can be correlated when more than one request is
in flight on a connection. Even though the initial client is single-threaded, a
protocol without correlation cannot gain concurrency later without a break.

**PTY credit** is flow control, the same idea as the server's credit window: the
client acknowledges bytes consumed, and the reader stops pushing when credit is
exhausted. Without it a `cat` of a large file grows the socket buffer without
bound. It is in the protocol from the start because retrofitting flow control into
a streaming protocol is a rewrite.

### 2.4 Threading model

| Thread | Count | Owns |
|---|---|---|
| Service main | 1 | lifecycle, watchdog |
| Socket accept | 1 | accepting connections |
| Per connection | 1 reader + 1 writer | framing, in/out queues |
| Per PTY session | 1 reader | the master fd, and closing it |
| API dispatch | bounded pool | short handler calls |

Bounds: a maximum of 16 concurrent connections, and the per-device PTY cap from
Build 1 (4) still applies. Both bounds exist so a client cannot exhaust threads.

The PTY reader is a **dedicated thread per session**, not pooled: it blocks in
`poll()` for up to the read timeout, and a pooled thread doing that would starve
the pool.

Handlers run on the dispatch pool, never on a socket reader thread, so a slow
handler cannot stall framing for that connection.

### 2.5 Token transport — presented once, per connection

The token is issued by Layer 1 when it spawns a session, and handed to the child
as an **environment variable** (§9.2 — not on disk, not in argv, where `ps` would
show it).

The child presents it in the **first frame** on a new connection
(`request` with `api: "auth"`). The server validates it once and binds the
connection to that token's scope. Every later frame on that connection is
implicitly scoped; no per-message token.

Why this and not the alternatives:

- **Per-message tokens** cost a validation and a lookup per call, and put a secret
  in every frame, which is more places to leak.
- **Connection-string tokens** (in the socket path or a URL) are not available here
  and would land in logs.
- **Handshake-once** validates one secret once, then the connection *is* the
  capability. That is the same model as the server's per-session bridge token, so
  the two sides stay conceptually aligned.

Token properties: 128-bit random, bound to
`{sessionId, deviceId, operatorId, apiAllowlist, expiresAt}`. Reusable for the
lifetime of the session across multiple connections, because a shell that runs
`android-battery` twice opens two connections and should not need two tokens.
Revoking the session revokes the token and closes every connection bound to it.

An unauthenticated connection may send only `auth`. Any other frame closes it. A
connection that does not authenticate within 5 seconds is closed, so an idle
socket cannot hold a slot.

---

## 3. Components

```
com.vyzorix.service/
  VyzorixForegroundService.kt   FGS, START_STICKY, owns every layer
  DeviceBootHook.kt             BOOT_COMPLETED -> start the service
  ServiceWatchdog.kt            restart the engine with backoff

com.vyzorix.bridge/
  BridgeProtocol.kt             frame encode/decode, the types above
  SessionTokens.kt              issue, validate, revoke
  AndroidSocketServer.kt        accept loop, per-connection reader/writer
  ApiRouter.kt                  api name -> handler
  apis/BatteryApi.kt            battery level, charging, temperature
  apis/DeviceInfoApi.kt         model, OS version, ABI, uptime
  apis/StorageApi.kt            free/total on internal storage
  apis/NetworkApi.kt            connectivity state, transport type
  apis/LogcatApi.kt             recent logcat lines (bounded)

com.vyzorix.capability/
  PtyReader.kt                  NEW — owns the master fd, pushes output
  PtySession.kt                 MODIFIED — ownership per §2.1

com.vyzorix.engine/
  ProotLauncher.kt              build the proot command line, exec on the slave
```

The five APIs are chosen to require **no runtime permission**. A permission prompt
would make the bridge untestable from `adb shell` without a UI, and the permission
flow belongs with the APIs that need it.

---

## 4. Risks to de-risk first

Same discipline as Build 1: a small spike before the full build, because each of
these either works or changes the design.

| # | Risk | If it fails |
|---|---|---|
| 1 | proot launches from app storage | proot is `ptrace`-based; SELinux must allow an app to trace its own child. Termux does this, so it should hold — but "should" is not "does". |
| 2 | a proot'd process can connect to `files/android.sock` | fall back to the abstract namespace + SCM_RIGHTS of the listening socket. |
| 3 | the fd handoff works end to end | if Layer 1 cannot hand a working fd to the container, USB (Build 3) loses its transport. |

**The spike proves 1–3 in one binary** before any service code is written:
launch proot on a PTY, from the proot'd shell connect to the socket and call
`device_info`, and pass the master fd across to prove the chain.

---

## 5. Definition of done

On the emulator, all of:

1. The service starts and survives; killing the process brings it back.
2. A client connects to `android.sock` and calls `battery`; a valid response returns.
3. A connection with **no token** is closed without serving a request.
4. A token scoped to `battery` calling `logcat` is refused.
5. Opening and closing 50 PTY sessions leaves the fd count unchanged — the Build 1
   leak is fixed and proven.
6. proot launches from app storage and runs a command.
7. A shell driven over the socket round-trips: input in, output out, flow control
   respected.
8. `./gradlew gate` passes; instrumented tests cover 1–7.

---

## 6. Non-goals

- **Not** a hardening pass. The constrained shell of §9.3 is deferred; the token
  model is built so that adding it is a scope check, not a redesign.
- **Not** the cloud. The socket is the only door in Build 2.
- **Not** multi-device. One device, one service.
