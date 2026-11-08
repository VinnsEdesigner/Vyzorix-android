# Filesystem access from the app's own uid

The host filesystem paths this app can reach, measured on the device. This is the
ceiling the platform imposes on a third-party app, and the reason the engine runs a
Debian userspace under `proot` instead of using the host filesystem directly (see
`ANDROID_SERVICE_ARCHITECTURE.md` §2).

## Scope

Access is a policy, not a fixed list, and it differs by API level. This records the
measurements for API 28 and API 33, the permission the shared-storage tree requires
on API 29+, and the host paths that remain denied.

## Probe method

`adb shell run-as <pkg>` does not measure the app's real access on API 29+:

```
adb shell run-as com.vyzorix.agent ls /storage/emulated/0    # invalid on API 29+
```

`run-as` runs in the `runas_app` SELinux domain, not the app's `untrusted_app`
domain, and in a different mount namespace. On API 33 the shared-storage mounts are
absent from that namespace, so every path reports `ABSENT`/`Permission denied`
regardless of the app's real access. Measured: `run-as` reported
`/storage/emulated/0` as inaccessible on API 33, while the app's own process could
read and list it.

An instrumented test runs in the app's real process, so it sees the app's real
domain and mounts. `FsAccessProbeTest` is that harness (see "Reproducing" below).

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

Two per-path details:

- `/` is denied but children are reachable: access is evaluated per path, not by
  traversing from the root.
- `/proc` is selectively readable: `/proc/cpuinfo` is readable while `/proc/version`
  is not. This is SELinux (`readproc` vs `readproc_extra`), not Unix mode.

## Measured: API 33 (Android 13)

Device: emulator `system-images;android-33;default;x86_64`, probed from the app's
own process via `FsAccessProbeTest`.

Without the storage permission, shared storage is read-only:

| Probe | Value |
|:--|:--|
| `isExternalStorageLegacy()` | `false` |
| `READ_EXTERNAL_STORAGE` granted | `false` |
| `WRITE_EXTERNAL_STORAGE` granted | `false` |

| Path | Result |
|:--|:--|
| `/sdcard`, `/storage/emulated/0`, `/mnt/sdcard` | list + read, write `Operation not permitted` |
| `…/Download`, `…/Documents` | writable (scoped-storage public collections) |
| `…/DCIM`, `…/Pictures`, `…/Music`, `…/Movies` | list + read, write denied |
| `…/Android/data`, `…/Android/obb` | denied |
| `…/Android/media` | list + read, write denied |
| `/data/user/0/com.vyzorix.agent/*` | full read/write |

The failure is a scoped-storage `Operation not permitted`, not a Unix permission
error. Anything writing under `/sdcard` fails in this state.

### Required permission

`READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`, declared in
`app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

and granted at runtime by `LaunchActivity` (both are dangerous permissions).

Scoped storage is gated on `targetSdk`. This app pins `targetSdk 28`, so it is on
the legacy path, but it still has to hold the permission to use it. With the
permission granted, `isExternalStorageLegacy()` returns `true` and access matches
API 28.

`WRITE_EXTERNAL_STORAGE` is a no-op from API 30 on — on Android 11+ write access to
the media collections comes from `READ_EXTERNAL_STORAGE` plus the app's own
`Android/media` — but declaring it keeps the API 26–29 range working, which is the
point of the pin.

`android:requestLegacyExternalStorage="true"` was tested and is not required: it
applies only to apps targeting API 29+, and this app targets 28.
`MANAGE_EXTERNAL_STORAGE` is not required: it is a Play-restricted "All files
access" permission, and the measurement shows the legacy path does not need it.

With the permission granted:

| Path | API 33 |
|:--|:--|
| `/sdcard`, `/storage/emulated/0`, `…/Download`, `…/DCIM`, `…/Pictures`, `…/Music`, `…/Movies`, `…/Documents` | read + write |
| `…/Android`, `…/Android/media` | read + write |
| `…/Android/data/com.vyzorix.agent/**` | read + write |
| `…/Android/data`, `…/Android/obb` (the parents) | denied — these hold other apps' data |
| `/data/user/0/com.vyzorix.agent/**` | read + write |

## Folders that remain denied

These are denied after the permission fix and need a `proot` bind (or an app-side
copy) if the container must see them:

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

Run it once as shipped and once with the permission granted, and compare.

## How this relates to the container

`proot` presents a Debian rootfs as `/`, and `ProotLauncher.binds()` selects the
**host** paths to expose inward:

- `/dev`, `/proc`, `/sys` — kernel interfaces the container needs
- the rootfs's `resolv.conf` — DNS
- the overlay (`usr-local`, `opt`, `etc`) — on-device installs that survive a rootfs swap
- `files/apps` → `/home/apps` — app storage (Layer 3)
- `files/android.sock` → `/tmp/android.sock` — the bridge

The bind list is the app's own allowlist, narrower than what the OS permits: §10 of
the architecture doc calls it the "closed world". A host folder beyond that list is
an explicit addition, and on API 29+ it must first be reachable by the app at all,
which is what this document records.

## Bound today (tier 1)

The launcher's list (`ProotLauncher.binds`) is:

| Host | In the container | Why |
|:--|:--|:--|
| `/dev`, `/proc`, `/sys` | same | Kernel interfaces the container needs |
| `rootfs/etc/resolv.conf` | `/etc/resolv.conf` | DNS; without it there is no name resolution |
| `overlay/usr-local` | `/usr/local` | Writable overlay, survives a rootfs swap |
| `overlay/opt` | `/opt` | Same |
| `overlay/etc` | `/etc/vyzorix` | Config that survives a rootfs swap |
| `files/apps` | `/home/apps` | App storage (Layer 3) |
| **shared-storage root** | **`/sdcard`** | The folders this document measures (below) |
| `files/android.sock` | `/tmp/android.sock` | The API bridge |

The shared-storage root is bound on **tier 1** (a stock, unrooted device). It is read
from `Environment.getExternalStorageDirectory()` rather than hardcoded, and skipped
when the device has none, so the list always equals what exists. Binding it exposes
the whole tree the app can reach — the public collections (Download, DCIM, Pictures,
Music, Movies, Documents) and `Android/media` — without naming each one.

This is a reversal of §6.1's "optional export target, never a dependency": shared
storage is now bound on every start, not only when an export feature asks for it.
`ProotLauncher.SHARED_STORAGE_IN_CONTAINER` is the single constant that records the
decision.

**A bind cannot grant more than the app already has.** proot does not change the real
uid, so a container process runs as the app's own uid, in the app's SELinux domain,
under the same Unix permissions. The bind changes what the container can *see*, never
what the app may *touch*. `/sdcard/Android/data`, `/sdcard/Android/obb` and other
apps' collections stay denied to the container exactly as they are denied to the app.

## Never bound

The app's own private tree (`context.filesDir`, `/data/user/0/<pkg>`) is **not** bound
as a whole. It holds the rootfs, the overlay, the secret store and the recordings, so
binding it would hand the container the keys to itself. Only named subdirectories cross
(`apps`, the socket) plus one file out of the rootfs for DNS. `isEngineZonePath` is the
second, independent check on the same rule, asserted in `ProotLauncherTest`.


