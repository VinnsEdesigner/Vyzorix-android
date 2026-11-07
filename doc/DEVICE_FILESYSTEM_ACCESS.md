# Filesystem access from the app's own uid

What the OS actually lets this app touch, measured on the device rather than read
from documentation. This is the **ceiling** the platform imposes on a third-party
app; it is the reason the engine runs a Debian userspace under `proot` instead of
using the host filesystem directly (see `ANDROID_SERVICE_ARCHITECTURE.md` §2).

## Why this document exists

Two questions come up repeatedly and neither is answerable from memory:

1. **"Which folders can the app access?"** The answer is a *policy*, not a list, and
   it changes by API level — drastically between Android 9 and Android 13, as the
   measurements below show.
2. **"Will it still work on a modern device?"** The app pins `targetSdk 28`, which
   *should* keep legacy storage semantics even on Android 10+. This document records
   the measurement that proves it, and the workaround that turned out to be needed.

## Read this first: `adb shell run-as` is not a valid probe

The obvious way to inspect an app's filesystem access is wrong on modern Android:

```
adb shell run-as com.vyzorix.agent ls /storage/emulated/0    # WRONG on API 29+
```

`run-as` runs in the **`runas_app` SELinux domain**, not the app's own domain
(`untrusted_app`), and — more importantly — in a **different mount namespace**. On
API 33 the shared-storage mounts are simply absent from that namespace, so every
path reports `ABSENT`/`Permission denied` regardless of what the app can really do.
Measured: `run-as` said `/storage/emulated/0` was inaccessible on API 33; the app's
own process said it was readable and listable.

**The only faithful probe is an instrumented test**, which runs in the app's real
process with the app's real domain and mounts. `FsAccessProbeTest` is that harness
(see "Reproducing" below).

## The three layers that decide access

A path is accessible only if **all three** allow it:

| Layer | What it is | What it does |
|:--|:--|:--|
| **SELinux** | The app's domain (`untrusted_app`) | Denies most of the filesystem by domain policy. The usual reason a path is denied even when the mode looks world-readable. |
| **Unix permissions** | The app's uid and supplementary groups | What grants shared storage: `sdcard_rw` (1015), `sdcard_r` (1028); on API 33+ also `ext_data_rw` (1078), `ext_obb_rw` (1079). |
| **Runtime permission / scoped storage** | `READ_/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, and the scoped-storage model | The framework gate *on top of* the two above. On API 29+ this is what actually decides whether the `/sdcard` tree is reachable at all. |

## Measured: API 28 (Android 9)

Device: emulator `system-images;android-28;default;x86_64`. Probed as the app uid
via `run-as` (valid here — pre-scoped-storage, so the domain/namespace split does
not yet hide anything).

### Writable

| Path | Notes |
|:--|:--|
| `/sdcard` → `/storage/self/primary` → `/mnt/user/0/primary` | The shared-storage mount, reachable through every alias |
| `/storage/emulated/0` | The real mount; `sdcard_rw` grants rwx |
| `/mnt/sdcard`, `/storage/self/primary` | Same mount, other aliases |
| `/storage/emulated/0/{Alarms,DCIM,Download,Movies,Music,Notifications,Pictures,Podcasts,Ringtones}` | The 9 standard media folders that exist by default |
| `/data/data/com.vyzorix.agent` (= `/data/user/0/…`) | Private tree: `files/`, `cache/`, `code_cache/`, `shared_prefs/` |

### Read-only

`/system`, `/system/lib64`, `/vendor`, `/product`, `/odm`, `/oem`, `/etc`, `/etc/hosts`,
`/proc/cpuinfo`, `/proc/meminfo`, `/proc/mounts`, `/proc/self/*`, `/dev/null` (also
writable).

### Denied

`/` (the root itself), `/data` (the parent), `/data/app`, `/data/local/tmp`,
`/data/system`, `/sys`, `/dev` (parent), `/dev/pts`, `/dev/bus/usb`, `/mnt`
(parent), `/storage/emulated` (parent), and most of `/proc` (`version`, `stat`,
`uptime`, `loadavg`, `filesystems`).

Two counter-intuitive details:

- **`/` is denied but children are reachable.** Access is per-path; there is no
  "traverse from the root" rule.
- **`/proc` is selectively readable.** `/proc/cpuinfo` is readable while
  `/proc/version` is not — SELinux (`readproc` vs `readproc_extra`), not Unix mode.

## Measured: API 33 (Android 13) — as shipped, without a workaround

Device: emulator `system-images;android-33;default;x86_64`, probed from the app's
own process via `FsAccessProbeTest`.

**This is a regression.** As the app ships today it declares **no storage
permission**, so the framework blocks shared storage entirely:

| Probe | Value |
|:--|:--|
| `isExternalStorageLegacy()` | **false** |
| `READ_EXTERNAL_STORAGE` granted | **false** |
| `WRITE_EXTERNAL_STORAGE` granted | **false** |

| Path | Result |
|:--|:--|
| `/sdcard`, `/storage/emulated/0`, `/mnt/sdcard` | list + read OK, **write → `Operation not permitted`** |
| `…/Download`, `…/Documents` | **writable** (the scoped-storage "public" collections) |
| `…/DCIM`, `…/Pictures`, `…/Music`, `…/Movies` | list + read OK, **write denied** |
| `…/Android/data`, `…/Android/obb` | **denied** (not even listable) |
| `…/Android/media` | list + read, write denied |
| `/data/user/0/com.vyzorix.agent/*` | full read/write (unaffected) |

So on Android 13 the app can *read* the shared tree but cannot *write* to it, and
the failure is a scoped-storage `Operation not permitted`, not a Unix permission
error. That would break anything the engine expects to write under `/sdcard`.

## The workaround (measured): declare the storage permission

Two changes were tested in isolation. **Only the first is required.**

| Change | Effect on API 33 |
|:--|:--|
| `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE` in the manifest, **granted at runtime** | **Full legacy access restored** — `isExternalStorageLegacy()` becomes `true` and the whole `/sdcard` tree is read/write again |
| `android:requestLegacyExternalStorage="true"` | **Not needed.** With the permission alone, `legacy=true` and access matches API 28 |

Why the permission alone is enough: scoped storage is gated on `targetSdk`, and this
app pins **`targetSdk 28`**. An app targeting < 29 is granted **legacy** external
storage by the platform — but it still has to *hold* the storage permission to use
it. `requestLegacyExternalStorage` exists for apps that target 29+ and want to opt
back in for one release; it is irrelevant at `targetSdk 28`.

Result with the permission granted — matches the API 28 table, with one addition:

| Path | API 33 with workaround |
|:--|:--|
| `/sdcard`, `/storage/emulated/0`, `…/Download`, `…/DCIM`, `…/Pictures`, `…/Music`, `…/Movies`, `…/Documents` | **read + write** |
| `…/Android`, `…/Android/media` | read + write |
| `…/Android/data/com.vyzorix.agent/**` | read + write (app-specific external dir) |
| `…/Android/data`, `…/Android/obb` (the parents) | still denied — correct, these hold *other* apps' data |
| `/data/user/0/com.vyzorix.agent/**` | read + write |

### Required change

Add to `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

and grant them at runtime (they are dangerous permissions). `WRITE_EXTERNAL_STORAGE`
is a no-op on API 30+ — on Android 11+ write access to the media collections comes
from `READ_EXTERNAL_STORAGE` plus the app's own `Android/media` — but declaring both
keeps the API 26–29 range working, which is the point of the pin.

`MANAGE_EXTERNAL_STORAGE` is **not** required and should not be requested: it is a
Play-restricted "All files access" permission, and the measurement shows it is not
needed for the legacy path.

## Folders that still need a workaround

After the permission fix, these remain denied by design and a `proot` bind (or an
app-side copy) is needed if the container must see them:

| Path | Why it is denied | Workaround |
|:--|:--|:--|
| `/data/local/tmp` | App-private to `shell`; `untrusted_app` cannot write it | Use `context.filesDir` (already the case for `PROOT_TMP_DIR`) |
| `/data/app`, `/data/system` | Other apps / system data | Not needed; nothing should read these |
| `/dev/bus/usb` | USB device nodes need a root/`UsbManager` path | `UsbManager` (the tier-2 path in the architecture doc) |
| `/sys` | Denied to `untrusted_app` | Bind only the specific `/sys` subpaths needed, if any |
| `/mnt/obb`, `/mnt/asec`, `/mnt/expand` | Legacy OBB/ASEC mounts, not for apps | Not needed |

## Reproducing

The probe is `tests/app/instrumented/kotlin/com/vyzorix/agent/FsAccessProbeTest.kt`.
It runs in the app's real process and prints a `PROBE_BEGIN … PROBE_END` block to
logcat:

```
adb logcat -c
./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64 \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vyzorix.agent.FsAccessProbeTest
adb logcat -d | grep System.out | sed 's/.*System.out: //' | grep -E 'PROBE|^[^|]+\|'
```

Run it once as shipped and once with the permission declared, and compare.

## How this relates to the container

The short list of accessible host paths is precisely why the engine does not use the
host filesystem. Instead `proot` presents a Debian rootfs as `/`, and
`ProotLauncher.binds()` chooses the handful of **host** paths to expose inward:

- `/dev`, `/proc`, `/sys` — kernel interfaces the container needs
- the rootfs's `resolv.conf` — DNS
- the overlay (`usr-local`, `opt`, `etc`) — on-device installs that survive a rootfs swap
- `files/apps` → `/home/apps` — app storage (Layer 3)
- `files/android.sock` → `/tmp/android.sock` — the bridge

The bind list is the app's *own* allowlist, and it is deliberately narrower than what
the OS would permit: §10 of the architecture doc calls it the "closed world". If the
container needs a host folder beyond that list, it is a deliberate addition — and on
API 29+ it must first be reachable by the app at all, which is what this document
records.
