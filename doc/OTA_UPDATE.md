# OTA / auto-update

How a device finds, downloads, verifies and installs a newer build. The mechanism is
automatic up to the install; the install is the user's to confirm (Android's rule,
not a gap — see below).

## The flow

```
device connects ──▶ GET /v1/check-update?version_code=N
                      │
                      ├─ update_available=false ─▶ done
                      │
                      └─ update_available=true
                             │
                             ▼
                    GET /v1/apk/<apk_filename>   (tens of MB)
                             │
                             ▼
                    SHA-256 vs manifest ─ mismatch ─▶ refuse, delete
                             │ match
                             ▼
                    installer intent ─▶ user taps ─▶ installed
```

Run on every socket connect (the same trigger as telemetry), so a device that has
been offline checks as soon as it can reach the server. It is not a poll loop: the
check is cheap, but the download is not.

## Device side

| Piece | What it does |
|:--|:--|
| `UpdateCheck` | The manifest DTO, mirroring the server's fields exactly. |
| `UpdateClient` | `check(versionCode)` and `download(check, file)`. The download **verifies the SHA-256** and refuses a mismatch or a missing hash. |
| `UpdateChecker` | The whole pass: check → download → verify → hand to the installer. Never throws; a failure means the device keeps its current build. |
| `ApkInstaller` | The seam for the install. `AndroidApkInstaller` (in `:app`) launches the system installer; a test records that the APK arrived. |

`UpdateChecker` runs from `CloudConnection.onConnected` on its own daemon thread, so
a 64 MB download cannot stall the socket reader.

### Why the install needs the user

A third-party app cannot install a package silently. `AndroidApkInstaller` launches
an `ACTION_VIEW` intent on the APK with the package-archive MIME type, and the user
confirms. A silent install needs a system signature or root. So the mechanism is
automatic; the last tap is consent.

The APK is exposed through a `FileProvider` (the installer runs in another process
and cannot read the app's private storage; a `file://` URI throws
`FileUriExposedException`). The provider's authority is `${applicationId}.fileprovider`
and it serves only `files/updates/`, the staging directory.

The manifest declares `REQUEST_INSTALL_PACKAGES`. Without it the intent is shown but
the install is refused.

## Server side

| Route | Auth | What it serves |
|:--|:--|:--|
| `GET /v1/check-update?version_code=N` | public | `update_available` plus the manifest fields, flattened |
| `GET /v1/version` | public | the manifest verbatim |
| `GET`/`HEAD /v1/apk/<name>` | public | the APK, with the manifest hash checked before serving |
| `POST /v1/download-progress` | public | the device's progress report; **stored**, not just logged |
| `GET /v1/download-progress/:deviceId` | public | the latest progress for a device, or `downloading:false` |

All four are registered both with and without the `/api` prefix, because the proxy
strips `/api`.

### Download progress

The device reports progress as it downloads — `POST /v1/download-progress` with the
device id, filename, and byte counts, fired when the whole-percent changes (a 64 MB
download in 8 KB reads would otherwise be thousands of requests). An operator reads
it back with `GET /v1/download-progress/:deviceId`.

The report was previously **log-only**: the server accepted progress and could not
answer for it, so nothing could check an update's progress. It now writes to an
in-memory `ProgressStore` keyed by device, with a 2-minute TTL — a device that stops
reporting (crashed, killed, offline) reads as `downloading:false` rather than as a
frozen percentage. The store is not durable on purpose: "how far along is this
download right now" is meaningless after a restart, and the device re-reports on its
next tick. It is the same lifetime as the PTY session registry, for the same reason.

The percent is derived from `bytesLoaded`/`totalBytes` so the two cannot disagree,
and the `progress` field the device sends is accepted but not trusted over them.

### Publishing an APK

```
tooling/scripts/generate_version.sh <apk> <version> <version-code> "<notes>"
```

It writes the two paths the **running server reads** — `apps/api/data/version.json`
and `apps/api/bin/<apk>` — not a separate copy. An earlier revision wrote
`api/v1/version.json` and `bin/`, which the server never read, so the served
manifest stayed a stub while the script reported success. Override with
`DATA_DIR` / `BIN_DIR` when the server runs elsewhere.

The manifest carries the SHA-256, so a device refuses an APK that does not match it.

## Where the release lives (read this before wiring a device to GitHub)

The APK release is in the **public** `VinnsEdesigner/gz-_ASSETS` repo:

```
https://github.com/VinnsEdesigner/gz-_ASSETS/releases/tag/app-v0.1.0
https://github.com/VinnsEdesigner/gz-_ASSETS/releases/download/app-v0.1.0/vyzorix-app-v0.1.0.apk
```

That download is **anonymous** (verified: HTTP 200, 64 MB, no token). It has to be:
the app repo `VYZORIX` is **private**, and GitHub serves a private
repo's release assets only to authenticated callers, so a device — which has no
token — gets a 404 from it. This is the same trap the rootfs assets hit (see
`ANDROID_SERVICE_ARCHITECTURE.md` §6.1.1). A release *was* created on the private
repo too, as a distribution record, but it is not the delivery path.

### The `make_latest: false` trap

The container rootfs is fetched via `releases/latest/download/...` (see
`rootfsBaseUrl()` in `app/build.gradle.kts`). So a new release in `gz-_ASSETS`
must **not** become "latest" — if it did, `releases/latest/download/debian-core-*.tar.gz`
would 404 and every fresh install would fail to fetch its userspace. The APK
release is therefore created with `"make_latest": "false"`, and `latest` still
resolves to `v1.0.0` (the container assets). Verified after publishing.

A device is still not wired to GitHub: it downloads from the **server's**
`/v1/apk/<name>` route. The public release is for the case where a device (or an
operator) should be able to fetch the APK without the server in the path.

## Verified live

Against a server on the host and the Android 9 emulator:

- `GET /v1/check-update?version_code=1` → `update_available:true`, manifest with a
  real 64 MB size and a real SHA-256.
- `UpdateLiveE2ETest` (2 tests, **0 skipped**) runs the production `UpdateClient`
  and `UpdateChecker` from the device: the check, the 64 MB download, and the
  checksum verification all happen, and the verified APK reaches the install step.
  The second test proves a device already on the latest code does **not** download.
- `HEAD /v1/apk/<name>` → 200 (it answered 400 before the HEAD routes were
  registered).
