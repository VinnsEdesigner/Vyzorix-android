# USB pillar — device APIs and the hw access tags

Status: **built and unit-tested; the hardware path is unverified.** The emulator
exposes no USB host devices, so enumeration and the fd handover can only be
confirmed on a real device with an OTG adapter. This records what is built, what
is proven, and what still needs hardware.

## The device APIs

Three APIs, all carrying the hardware capability tags.

| API | Operation | What it does |
|:--|:--|:--|
| `usb_state` | read | Lists attached devices with vendor/product id, class, product/manufacturer, serial (when permitted), interface count, permission state, and whether the bridge holds it open. |
| `usb_attach` | **write** | Opens a device by name and holds its file descriptor. The fd is the capability the container will receive. |
| `usb_detach` | **write** | Closes a device and drops its fd. Idempotent. |

They are the first write APIs in the catalogue, which is why `op:write` appears
now: the tag vocabulary grows with the APIs that need it, and a tag nothing names
is inert.

## The capability tags

| Tag | Why these APIs carry it |
|:--|:--|
| `hw:usb` | The API touches USB hardware. The dimension exists for exactly this. |
| `data:hardware` | The attachment list names physical devices, which is more sensitive than the public facts `battery`/`device_info` report. |
| `op:write` | `usb_attach`/`usb_detach` change state — they claim and release a device. `usb_state` is `op:read`. |
| `protection:signature` | Matches the server catalog's USB capability class. There is no manifest permission for "use a USB device"; the per-device grant is a dialog the app raises. |
| `visibility:private` | The device list is for a user, not the public read surface. |

The device's `@VyzorixApi` annotation is documentation — the device never
evaluates a tag — but the server's catalog is the authority, and the two must use
the same words. `ApiTagsTest` (device) and `TestNoInertTags` (server) enforce that
from both ends.

## The server catalog

The permission package's `DeviceAPICatalog` gains three entries, one per API, each
naming the same `ActionUsb` action as the existing `usb` capability:

| API | Action | MinTier |
|:--|:--|:--|
| `usb_state` | `usb.attach` | 1 (unrooted) |
| `usb_attach` | `usb.attach` | 1 (unrooted) |
| `usb_detach` | `usb.attach` | 1 (unrooted) |

`MinTier` is 1 for all three: the `UsbManager` path serves an unrooted device, and
that is the whole point of the fd route — `/dev/bus/usb` is unreachable there. The
rooted-only `usb_nodes` entry stays at tier 2.

Resolution is unchanged: a role reaches these only if it holds `hw:usb` and
`data:hardware`, which is `admin`/`super_admin`. A viewer or operator reaches none
of them at any tier — tier caps the allowlist, it does not grant. `defaults_test.go`
asserts both directions.

## The fd capability

`usb_attach` returns the device's file descriptor. That descriptor is the grant:
closing it revokes access, and the token's allowlist is a filter on top of it, not
the capability itself (architecture §9.1). The container-side handover —
`SCM_RIGHTS` over the bridge socket, then `libusb_wrap_sys_device` in the Debian
userspace — is the next slice and is **not built**. Until it is, `usb_attach`
holds the fd in the app process and nothing downstream can use it.

## What is verified, and what is not

| Claim | Status |
|:--|:--|
| The three APIs register and are reachable (`apis.txt`, router) | **Verified** — `ApiCatalogTest`, `ApiSmokeTest` |
| The handlers answer rather than throwing | **Verified** — `ApiSmokeTest` with a no-device host |
| The tags match the server catalog | **Verified** — `ApiTagsTest`, `TestNoInertTags` |
| The server resolves the APIs only for `hw:usb` holders | **Verified** — `defaults_test.go` |
| Enumeration reports real attached devices | **Not verified** — needs hardware |
| `openDevice()` returns a usable fd, and it survives to a control transfer | **Not verified** — needs hardware |
| The fd handover to the container | **Not built** |

The emulator has only root hubs (`/sys/bus/usb/devices` shows `usb1`–`usb3`, no
peripherals), so `usb_state` returns an empty list there. That is the correct
answer, not a bug — but it means the hardware path is untested until a device with
an OTG adapter is available.

## Known limits (from architecture §4.2 — document, do not fight)

- Kernel driver auto-claim (`cdc_acm` on serial adapters) blocks `claimInterface`;
  detaching needs root.
- Isochronous transfers are weak → fine for flashing/dumping, bad for USB audio/video.
- OTG supplies roughly 100–500 mA; high-draw devices need a powered hub or Y-cable.

## Reproducing on the emulator

```
./gradlew :android-core:testDebugUnitTest --tests "*ApiTagsTest" \
    --tests "*ApiCatalogTest" --tests "*ApiSmokeTest"
```

For the on-device path, `:app`'s `ApiLiveE2ETest` calls `usb_state` through the
container like every other API; it will report `{"devices":[],"count":0}` on an
emulator.
