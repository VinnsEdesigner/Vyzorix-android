# `native/lib` — the C++ the container tools share

The container tools (`debian-container/android-bin`, `app-runtime/supervisor`,
`app-runtime/cli-wrappers`) run **inside the Debian userspace**, whose libc is glibc
— not Android's bionic — so this library is built by a GNU cross toolchain, never the
NDK. Each tool links the translation units it needs rather than a shared object:
there is nothing to install into the rootfs, and a static link keeps every tool
self-contained.

## What is here, and why each file is named for the thing it is

File names say what the file *is*, not what layer it belongs to — `AGENTS.md`'s rule
for the Kotlin applies here too, and a directory of `json.cpp`, `log.cpp`,
`meta.cpp` would describe categories rather than things.

| File | What it is |
|:--|:--|
| `vyzorix_json.h/.cpp` | The JSON reader and writer. Hand-rolled: a dependency is one more package that must exist in the rootfs for every architecture, and the surface — the shapes bridge frames carry — is small. |
| `frame_channel.h/.cpp` | The repo's one wire shape, `[4B length][1B type][payload]`, over a unix socket. The bridge speaks it and so does the supervisor's control socket, so there is one framing and one set of bugs. |
| `app_registry.h/.cpp` | `apps.json`: what runs. Atomic save, tolerant load, and the PID start-time token read from `/proc/<pid>/stat` field 22 — a bare pid must never be trusted. |
| `app_dir_watcher.h/.cpp` | inotify on the apps directory, so an edited registry is picked up without a restart. Watches the *directory*, because `Save` renames and would kill a watch on the file. |
| `app_log_rotation.h/.cpp` | Bounded per-app logs. Copy-then-truncate, which is only safe because the log is opened `O_APPEND`. |
| `app_meta_store.h/.cpp` | `meta/<name>.json`: per-app state outside the registry. Holds the expose config, which **contains a token**, hence a separate file, mode 0600, and not `apps.json`. |
| `app_http_proxy.h/.cpp` | The reverse proxy that makes an exposed app reachable. Where the token rule is *enforced* rather than advised. |
| `native_lib_test.cpp` | The tests for all of the above, on the host: `make check`. |

## Layout on the device

Both live under `/home/apps` inside the container, bound from the app's private
storage so they outlive a rootfs replacement:

```
files/apps/           ->  /home/apps
├── apps.json             the registry
├── logs/<name>.log       one per app, rotated
├── meta/<name>.json      one per app, 0600 — holds the expose token
└── supervisor.sock       the control socket
```

## Building and testing

```bash
make check    # build and run the tests, on the host
make          # build the objects
```

The consumers cross-compile it themselves:

```bash
make CXX=aarch64-linux-gnu-g++     # arm64
```

`scripts/install-toolchain.sh` installs both cross-compilers.
