# `android-bin` — the container-side bridge client

One binary that speaks to Layer 1's `android.sock`, invoked under one name per
Android API. `battery` is the API; `battery` is also a symlink to `android-bin`;
running it calls the bridge and prints the result.

This is the piece that makes the bridge *usable from inside the container*: without
it, a process in the Debian userspace has a socket it cannot speak to. It is Layer
2's last missing piece (architecture doc §3, §12.1).

```
debian-engine/android-bin/
├── apis.txt          the API names, one per line — the single source of truth
├── generator.cpp     host tool: apis.txt -> api_catalog.cpp + the symlinks
├── Makefile
├── src/
│   ├── bridge.cpp    the android.sock client: framing, auth, request/response
│   ├── bridge_main.cpp   the CLI (argv -> args, --list, --help)
│   ├── json.cpp      a small JSON reader/writer (no dependencies)
│   ├── json.h
│   └── api_catalog.h the generated catalog's interface
└── test/
    └── json_test.cpp the JSON unit tests, run by `make check`
```

## Building

The binary runs **inside the Debian userspace**, whose libc is glibc — not Android's
bionic — so it is cross-compiled with a GNU toolchain, **never the NDK**:

```bash
make CXX=g++                       # on x86_64, for an amd64 rootfs
make CXX=aarch64-linux-gnu-g++     # for arm64
make CXX=arm-linux-gnueabihf-g++   # for armhf (armeabi-v7a)
make check                         # the JSON unit tests, on the host
```

`scripts/install-toolchain.sh` installs both cross-compilers. The build produces
`build/bin/`, which is the **installable unit**: the binary plus its symlinks,
copied into the rootfs overlay as one directory.

## The one rule: apis.txt is the source of truth

`apis.txt` lists the API names. One pass of `generator.cpp` over it produces both
the generated `api_catalog.cpp` (what `--list` prints, what the unknown-name check
reads) and the symlinks. They cannot disagree because they come from one file.

**The list must match the handlers Layer 1 registers.** The authority is
`VyzorixForegroundService.buildRouter()` in `android-core`. A name here the router
does not serve fails at call time with `unknown_api`; a handler the router serves
that is missing here is simply not reachable as a command. `ApiCatalogTest` (in the
`:android-core` unit suite) asserts the two agree, so the drift fails the gate
rather than surfacing on a device.

## How it is used

The proot launcher starts the container's login shell with the bridge socket and a
session token in its environment (architecture doc §9.2):

```
VYZORIX_BRIDGE_SOCKET=/tmp/android.sock
VYZORIX_BRIDGE_TOKEN=<per-session capability token>
```

`android-bin` reads both from the environment — there is no config file and no
secret on disk. Inside the container, from that shell:

```bash
battery                          # prints the battery API's JSON result
battery --json '{}'              # explicit empty args
network iface=wlan0              # key=value pairs become the args object
android-bin --api battery        # the same call without the symlink
android-bin --list               # the API names this binary knows
```

## Wire contract

The frame shape is architecture doc §2.3, implemented on the Kotlin side in
`android-core/.../bridge/BridgeProtocol.kt`. The two must agree byte for byte:

```
[4B length][1B type][payload]
```

`length` is the payload length, big-endian, capped at 1 MiB. The connection
authenticates once (an `auth` request carrying the token), then issues requests and
reads responses. `json_test.cpp` pins the three response shapes the bridge sends —
the auth response, a success, and an error — so a change on either side that breaks
the contract fails a test rather than a device.

## Why the JSON is hand-rolled

It is a few hundred lines that build anywhere, against a general JSON dependency
that would be one more package that must exist in the rootfs for every
architecture. The surface is small — the shapes bridge frames carry — and
`json_test.cpp` covers it. It is not a general-purpose library and should not be
grown into one; if a real need appears, take a real dependency then.
