# `doc/` — index

Read this before opening a document, so you open the right one.

## The authority

**`ANDROID_SERVICE_ARCHITECTURE.md`** is the authoritative design for the Android
side. Read it before changing anything structural; it records *why* the awkward
constraints exist (the `targetSdk 28` pin, the fd-capability model, the single-WS
owner). Every other document here either refines it or records a build against it.

If a document disagrees with it, the architecture doc wins.

> **A note on the root `README.md`.** Earlier revisions of this repository carried a
> `README.md` and two sync workflows belonging to a *different* project
> (VyzorixAudioRouter — audio routing, "Layers 0–8", `MediaProjection`). They were
> deleted on 2026-11-04 because they described a repo this is not, and a **new,
> correct** `README.md` was written for this repo on 2026-11-05. There is **no
> audio engine** in this design. Do not resurrect the old one.

## Status at a glance

| Layer | What | State |
|:--|:--|:--|
| 1 — Android Gateway | FGS, boot, watchdog, PTY broker, `android.sock` | **Built** (`android-core`) |
| 4 — Cloud Connector | enroll, device WS, config, commands, telemetry, terminal | **Built** (`cloud-connector`) |
| 2 — Debian Container | proot, rootfs, overlay, `android-bin` | **Spec'd** (§6.1.1), pending its rootfs asset |
| 3 — App Runtime | supervisor, `app-*`, inotify | Pending |

## The documents

| Document | What it is | Status |
|:--|:--|:--|
| `ANDROID_SERVICE_ARCHITECTURE.md` | **The authority.** 4-layer design, platform constraints, the fd-capability model, the PTY wire contract, the build order, the rootfs asset spec (§6.1.1). | v3 + 2026-11-04 corrections |
| `REGISTRATION_CONTRACT.md` | How a fresh device becomes a known, owned device in one account: the Keystore keypair, enroll, the pairing code, config delivery. Supersedes the inbox/confirm flow. | Design, agreed in outline; **implemented** |
| `BUILD_2_CONTRACT.md` | The service host (Layer 1): scope, fd ownership, socket namespace, framing, threading, tokens, definition of done. | Historical contract, **met** |
| `BUILD_2_VERIFICATION.md` | The emulator run that turned Build 2 from "compiles" into "proven", and the three device-only bugs it found. | **Pass** for items 1–5, 8; 6–7 blocked on the rootfs |
| `BUILD_3_REQUIREMENTS.md` | The cloud side: the server blockers, the wire contracts byte for byte, the file list, the tests. | **Done** — read as the contract it implemented |
| `PTY_SPIKE_RESULTS.md` | The experiment that de-risked the PTY pillar: `/dev/ptmx` reachable, and `execve` from app storage works at `targetSdk 28`. | **Pass** on emulator; real-device confirmation outstanding |
| `LAYER_3_REQUIREMENTS.md` | Layer 3 (the app runtime) as a work item: components, the contracts it sits between, the decisions needed first, the build order. | **Proposed, for discussion** — nothing built |

## Elsewhere

- `AGENTS.md` (repo root) — build commands, the gate, toolchain pins, emulator notes.
  Read it before running anything.
- `VYZORIX/DOCUMENTATION/` — the server side (`COMMAND_EXECUTION.md`,
  `RISK_AND_CONFIRMATION.md`). Separate repository.
- `VYZORIX/DOCUMENTATION/CONTAINER_ANDROID_APIS.md` — what a
  session may do once registered. Also in the server repository.
