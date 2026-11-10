# Layer 3 — App Runtime: requirements

> Status: **proposed, for discussion.** Nothing here is built. This is the
> requirements pass a senior lead does before code: what Layer 3 is, what exists,
> what it must do, the contracts it sits between, and the decisions that must be
> taken first. The authority for the design is
> `ANDROID_SERVICE_ARCHITECTURE.md` (§2, §3, §5, §7, §8, §11, §12.1); this document
> refines it into work and does not override it.

---

## 1. What Layer 3 is

**One sentence:** the thing that runs *applications* inside the container and keeps
them running — a supervisor, a small CLI to drive it, a proxy to reach what it
runs, and the storage that makes an app's state survive a rootfs replacement.

Layer 2 gave the device a Debian userspace with a shell. Layer 3 turns that
userspace from "somewhere you can type" into "somewhere things run": an app is
started, supervised, restarted when it crashes, restarted when the device reboots,
reachable over the network, and its files persist across container updates.

**What it is not.** It is not a second cloud client (§2: layers 2–3 speak local
unix sockets only), it is not an Android API surface (that is Layer 1), and it does
not own the container lifecycle (Layer 1 launches proot).

---

## 2. Where it sits

```
Layer 1  (Kotlin + NDK)   FGS · PTY broker · android.sock · cloud WS
   │  spawns proot, relays APP_* commands, proxies HTTP
   ▼
Layer 2  (Debian container)  bash · android-bin · the userspace
   │  the supervisor lives here, and the apps run under it
   ▼
Layer 3  (C supervisor)   app lifecycle · inotify · http_proxy · /home/apps
```

Two directions of traffic, and they are different:

- **Control, inbound from the cloud.** `APP_START` / `APP_STOP` arrive as signed
  commands on the device WebSocket (§5), are executed by Layer 1's dispatcher, and
  must reach the supervisor. Layer 1 already has the executor seam
  (`CommandExecutor`, `ShellExecExecutor`) and an `APP_START`/`APP_STOP` is just
  another executor.
- **Data, outbound to a browser.** An app listens on a port inside the container;
  an operator reaches it through the app proxy — localhost for the same phone,
  the hotspot with a token, or the cloud relay (§11). Layer 1 owns the socket, so
  the proxy is a relay, not a server.

---

## 3. Current state (measured, not assumed)

| Piece | State | Evidence |
|:--|:--|:--|
| Container + shell | **built** | `ContainerTerminalE2ETest` passes on the emulator |
| Bridge client in-container (`android-bin`) | **built** | Layer 2, this repo |
| `SHELL_EXEC` command executor | **built** | `cloud-connector/.../ShellExecExecutor.kt` |
| `CommandExecutor` seam | **built** | `cloud-connector/.../CommandExecutor.kt` |
| `APP_START` / `APP_STOP` executors | **missing** | `CloudConnectionFactory` registers only `SHELL_EXEC` |
| `app-runtime/` (supervisor, CLI, web-ui) | **does not exist** | no directory |
| `AppProxyRelay.kt` | **does not exist** | §5 lists it; not in the tree |
| Persistent app storage (`/home/apps`) | **missing, and not bound** | `ProotLauncher.binds` has no `/home` entry — see §5.1 |
| Supervisor socket | **undefined** | not in the doc; must be designed here |
| Server app routes (`apps.read`/`apps.write`) | **not started** | §13: scopes exist, no route checks them |

So Layer 3 is genuinely greenfield: no code, and three gaps in the layer *below* it
that it depends on.

---

## 4. Components and their requirements

### 4.1 `supervisor` — the process that supervises (C, inside the container)

**Does (§7):** fork/exec, autostart, crash restart with backoff, `inotify` on the
app directory, the app registry, and a control socket.

Requirements, each testable:

| # | Requirement | Acceptance |
|:--|:--|:--|
| S1 | Reads `apps.json`, starts every app marked `autostart` | unit: given a fixture, the started set matches |
| S2 | Each app runs in its own process group | a crashing app does not take the supervisor down |
| S3 | Restarts a crashed app with exponential backoff, capped | unit: backoff sequence is 1s, 2s, 4s… capped at N |
| S4 | Does not restart an app that exited 0 when it is one-shot | unit |
| S5 | **PID validation by start-time token, not bare PID** | unit: a reused PID is not mistaken for our process (`/proc/<pid>/stat` field 22) |
| S6 | `inotify` on the app directory: a new/changed manifest is picked up | unit + on-device |
| S7 | Forwards app stdout/stderr to a bounded log, rotated | unit: rotation at the size cap |
| S8 | Serves a control socket: start, stop, restart, list, logs, env | unit against a socketpair |
| S9 | Never dials the cloud; never talks to Android | by inspection: no socket to anything but the control socket |
| S10 | Survives a rootfs replacement (its state is not in the rootfs) | on-device: replace rootfs, supervisor state intact |

**Open (decision D3):** who starts the supervisor? Options in §6.

### 4.2 `app-*` CLI — the operator's handle inside the container

The doc names eight (§3): `app-start`, `app-stop`, `app-restart`, `app-list`,
`app-logs`, `app-env`, `app-create`, `app-expose`.

Requirement: each is a thin client of the supervisor's control socket, prints
machine-readable output, and exits non-zero on failure. The behaviour is identical
whether invoked by an operator in a terminal or by a script.

**Open (decision D1):** language. The doc says "bash wrappers"; the repo's
convention is C++ for container tools (that is what `android-bin` is, and its
bridge client already does framed-socket I/O we could reuse).

### 4.3 `apps.json` + the app manifest — the registry

`apps.json` per §3: `{name, dir, cmd, port, env, autostart, pid, pid_start_ticks}`.

Requirements:
- **A3.1** The registry is the single source of truth for what runs; `app-list`
  reads it, the supervisor is driven by it.
- **A3.2** `pid` and `pid_start_ticks` are written by the supervisor and are
  authoritative only together (§7.1).
- **A3.3** Writes are atomic (write-temp-then-rename): a crash mid-write must not
  leave a registry that will not parse.
- **A3.4** An app's own manifest (in its directory) can add it; `inotify` picks it
  up. **Open (decision D5):** one registry or per-app manifests, or both.

### 4.4 `http_proxy` — reaching what an app serves

**Does (§7.2, §11):** make an app's port reachable, with the bind address and auth
being explicit and *correct*.

Requirements, and the security ones are the point:

| # | Requirement | Acceptance |
|:--|:--|:--|
| P1 | Default bind is `127.0.0.1` | on-device: not reachable from another interface |
| P2 | `0.0.0.0` is **refused without a token** — no unauthenticated LAN exposure | unit: `--bind 0.0.0.0` with no token fails closed |
| P3 | A token, when required, is checked on every request, and compared in constant time | unit |
| P4 | Streaming and chunked bodies pass through without buffering the whole body | unit: a large response is streamed, memory stays bounded |
| P5 | A request that never ends is timed out, both directions | unit |
| P6 | `Host`/`Origin` are not trusted for authorization | by inspection + unit |

### 4.5 `web-ui` — the offline page

§3 lists it; §11.1 is its purpose (`app-start flasher --bind 127.0.0.1` → Chrome →
`localhost:3000`). Requirement: a page that lists running apps and links to their
proxied ports, served from the device with **no network** and no external assets.

**Open (decision D6):** in v1, or defer? It is small but it is not on the critical
path to "apps run".

### 4.6 `AppProxyRelay.kt` — Layer 1's half (Kotlin)

§5's `http_proxy` frames ↔ supervisor, and §10's "App proxy" row is server work
plus this. Requirement: Layer 1 relays bytes between the cloud socket's proxy
frames and the supervisor, and **does not** terminate TLS or rewrite HTTP (that is
the server's proxy, §7.3). This is the piece that makes mode 3 (§11.3) work.

### 4.7 The `APP_START` / `APP_STOP` executors (Kotlin, Layer 1)

Small but on the critical path: two `CommandExecutor`s registered in
`CloudConnectionFactory` that call the supervisor's control socket. Requirements:
- **C1** `APP_START`/`APP_STOP` are registered (today only `SHELL_EXEC` is).
- **C2** An unknown command type still reports **failed**, never silence (§5).
- **C3** A control-socket failure is reported as a failed command with the reason.

---

## 5. Storage — and a gap in Layer 2 it depends on

### 5.1 `/home/apps` must be bound into the container (missing today)

§2 says Layer 2 is disposable but **"Layer 3's storage is not"**. `apps.json`,
app directories and their data must survive a rootfs replacement. Today
`ProotLauncher.binds` binds `/usr/local`, `/opt` and `/etc/vyzorix` from the
overlay — **there is no `/home` entry**, and §7 requires `inotify` on `/home/apps`.
So either:

- **(a)** add `overlay/home:/home` (and seed it), or
- **(b)** bind a dedicated app-storage dir, e.g. `files/apps:/home/apps`.

(a) is one bind and matches the overlay model; (b) is more explicit about "this is
app storage, not package storage". **Decision D2.**

### 5.2 Logs and data are bounded

App logs rotate at a size cap (§4.1 S7); app data is the user's business but the
storage check must not be assumed — §6.1.1 says "never assume `/data` has room
without asking `StatFs`".

---

## 6. Decisions needed before code

| # | Decision | Options | My recommendation |
|:--|:--|:--|:--|
| **D1** | `app-*` CLI language | bash wrappers (as doc says) / C++ / shell over a shared C++ client | **C++**, reusing `android-bin`'s framed-socket code. Bash cannot parse the registry reliably and the repo has no bash test harness. |
| **D2** | App storage | `overlay/home:/home` / `files/apps:/home/apps` | **`files/apps:/home/apps`** — explicit, and it keeps `/home` (users) distinct from app storage. |
| **D3** | Who starts the supervisor | Layer 1 spawns it as a PTY session / the login shell starts it / a systemd-less init script in the overlay | **Layer 1 spawns it**, on the same install thread that launches the container — it must exist before any `APP_*` command arrives, and Layer 1 already owns process launching. |
| **D4** | Supervisor control protocol | reuse the bridge frame shape (`[4B len][1B type][payload]`) / a new line-based protocol | **Reuse the frame shape.** One wire shape in the repo, and `android-bin`'s client already implements it. |
| **D5** | App registry | one `apps.json` / per-app manifests / both | **Both, one direction:** `apps.json` is authoritative for what runs; a manifest is how an app is *declared* and gets merged in. |
| **D6** | `web-ui` in v1? | yes / defer to v1.1 | **Defer.** It is not needed for "apps run and are reachable", and §14.2 already suggests deferring the expose work. |
| **D7** | `app-expose` scope | full wildcard-DNS/TLS proxy in v1 / hotspot + cloud-relay modes only | **Defer the wildcard proxy** (§7.3 calls it "a real project", build last). Ship modes 1 and 2 of §11. |
| **D8** | Server-side app routes | build `apps.read`/`apps.write` routes now / leave for the server team | **Leave**, and say so. §13: the scopes exist but no route checks them; Layer 3 can be complete and testable without them. |

---

## 7. Scope split

**Layer 3 v1 (this build):**
1. Supervisor: registry, lifecycle, backoff, PID start-time validation, logs, control socket.
2. `app-*` CLI: start, stop, restart, list, logs, env, create.
3. Persistent `/home/apps` bind + seeding (D2).
4. `APP_START` / `APP_STOP` executors in Layer 1.
5. `http_proxy` with `127.0.0.1` default and token-gated `0.0.0.0` (modes 1 and 2 of §11).

**Deferred, with reasons:**
- `app-expose` wildcard DNS/TLS proxy (§7.3, D7) — "cloudflared-lite", build last.
- `AppProxyRelay` + cloud-relay mode (§11.3) — needs the server proxy routes.
- `web-ui` (D6).
- Server `apps.*` routes (D8).

---

## 8. Build order inside Layer 3

Each step is independently verifiable, and later steps depend on earlier ones:

1. **`apps.json` schema + atomic read/write + tests.** Nothing works without the registry.
2. **Supervisor core**: load registry, fork/exec, process groups, exit handling.
3. **PID start-time validation** (S5) — small, and retrofitting it later means
   revisiting every place a PID is trusted.
4. **Restart with backoff** (S3/S4).
5. **Control socket** (S8) — this is what the CLI and Layer 1 both need.
6. **`app-*` CLI** (4.2).
7. **`inotify`** (S6).
8. **`/home/apps` bind** (5.1) — must land before the supervisor is spawned in production.
9. **Layer 1 executors** (4.7) — the cloud can then start and stop apps.
10. **`http_proxy`** (4.4).
11. **Logs + rotation** (S7).

---

## 9. Testing strategy

Layer 3 is C in a container, so the tiers are:

- **Unit, on the host** (like `android-bin`'s `make check`): registry, backoff
  sequence, PID-token comparison, proxy token check, log rotation. Pure logic,
  no container.
- **Integration, in a real Debian userspace**: the supervisor started in the
  container, driving a trivial app (`/bin/sleep`), over its control socket. This
  is the analogue of `ContainerTerminalE2ETest` and belongs in `:app`'s instrumented
  suite, because `:app` is the only module with the rootfs asset.
- **On-device, end to end**: `APP_START` from the cloud reaches the supervisor and
  an app comes up; a crash restarts it; a rootfs replacement leaves app data.
- **The invariant tests**: the supervisor opens no network socket (S9); a
  `0.0.0.0` bind without a token is refused (P2).

The gate must cover the new C: `scripts/native-gate.sh` already discovers
`*.c`/`*.cpp` outside `third_party/`, so a `app-runtime/` tree is linted and
formatted automatically — with the same clang-tidy caveat as `android-bin` (no
AGP compile DB, so tidy needs a compile database or the same split).

---

## 10. Risks

| Risk | Why it matters | Mitigation |
|:--|:--|:--|
| **PID reuse** | restarting the wrong process; §7.1 exists because of it | start-time token from the first version (S5), never bare PID |
| **Unauthenticated LAN exposure** | `0.0.0.0` is every interface, and a flasher on a hotspot is reachable by anyone | refuse `0.0.0.0` without a token (P2), fail closed |
| **Phantom-process killer** | Android 12+ kills ~32 concurrent children; apps add children | the doc accepts it (§1.4); keep the app cap low and document it |
| **Supervisor outliving a token rotation** | the same shape as the tmux finding: a long-lived process keeps its environment | state it; a supervisor restart picks up the new token |
| **`/home/apps` not bound** | app data silently vanishes on a rootfs update — the §2 invariant broken | D2, and land it before the supervisor ships |
| **Rootfs replacement races a running app** | binaries disappear under a running process | `kill-on-exit` + supervisor restart; document the ordering |

---

## 11. What "done" looks like

1. `app-create` makes an app; `app-start` runs it; `app-list` shows it running.
2. It is reachable on `127.0.0.1:<port>`; `0.0.0.0` requires a token.
3. Kill it; the supervisor restarts it with backoff.
4. Reboot the device; autostart apps come back.
5. Replace the rootfs; app data and `apps.json` survive.
6. `APP_START` dispatched from the cloud starts an app on the device.
7. `./gradlew gate` is green, including the new C.
