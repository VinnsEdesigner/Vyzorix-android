# The Vyzorix CLI

The device's own tool, inside the container: the APIs as commands. You install it
by installing the agent — it is the app runtime — and you type into it from the
terminal the cloud gives you.

This is **not** the deploy CLI. `vyzorix-cli` (the Go tool, `vyzorix login` /
`vyzorix deploy`) runs on your machine and pushes manifests to a fleet. This one
runs **on the phone**, in the container, and reaches the device's APIs. Two tools,
two places; the name "Vyzorix CLI" belongs to the device one because it is the one
whose command list *is* the product.

---

## What it is

Three pieces ship together as the app runtime (the "Vyzorix CLI bundle"):

```
bin/vyzorix        the front door — lists and dispatches
bin/android-bin    the bridge client; one symlink per API (camera, battery, wboot, …)
bin/supervisor     the app-lifecycle daemon
bin/app-*          app management (app-start, app-list, app-create, …)
```

`vyzorix` is a shell script; the rest are cross-compiled C/C++ for the container's
glibc. The bundle is built by `scripts/build-vyzorix-cli.sh` and published as
`vyzorix-cli-<abi>.tar.gz`.

## How it gets onto the device

Two paths, and the APK prefers the first:

1. **Bundled.** The APK carries the bundle at `assets/vyzorix-cli/vyzorix-cli.l3`.
   On first start the service extracts it to the overlay's `/usr/local`, which is
   what the container sees as `/usr/local/bin`. No network, no wait.
2. **Fetched.** A build that does not bundle it downloads
   `<baseUrl>/vyzorix-cli-<abi>.tar.gz` and verifies it against
   `VYZORIX_CLI_SHA256` compiled into the APK. The tarball is built **reproducibly**
   (fixed mtime/owner/order, `gzip -n`) so that checksum is stable.

Either way it lands at the same place: `bin/` and `lib/` under `/usr/local`, so
`vyzorix` is on the container's `PATH`.

## Using it

```bash
vyzorix                     # who you are, and the commands you can run
vyzorix whoami              # just the identity
vyzorix battery             # call one — prints JSON, exits non-zero on failure
vyzorix camera --help       # a command's own help
vyzorix version
```

Run it bare and it groups the commands by area (Hardware, Connectivity, Telephony,
Apps, Device, System) and shows **only the ones your session may call**. The rest
are dimmed, so you can see what exists and what is out of your scope — a scoped
terminal, not a wall.

Every command prints JSON and exits non-zero on failure, so it works from a shell
and from a script.

## The identity, and why the list is short

A terminal is opened by an operator over the cloud, and the server decides what
that operator may do. It resolves the capability from the operator's **role**
intersected with the **device's tier** and any scoped grant, and sends the result
with the session:

```
identity  operator · name · email · org · role (+ human label) · tier · expiry
allowlist the concrete API names the session may call
```

The device delivers that to the shell as the `VYZORIX_SESSION` environment
variable, and `vyzorix` reads it:

- **`whoami`** shows your name, human role ("Super admin", not `super_admin`), org,
  email and when the session ends.
- **the list** is filtered to the allowlist.
- **a call outside the allowlist** is refused with a reason before the socket is
  even opened — *"wboot is not in your scope (container_status)"*.

### Where the rule is enforced

The **bridge is the enforcement point.** The session token the shell carries is
scoped to the server's allowlist, and `ApiRouter` refuses any call outside it. The
CLI's check is the *explanation*: it turns a raw `forbidden` into a sentence a
person can act on. One enforcement point; the CLI never claims to be it.

### Default deny

If the server sent **no** identity — an older server, or a server-initiated
reattach — the session is issued the **empty** allowlist, not the full one. The
shell still opens (the container's own tools are not bridge APIs), but every device
API is refused. A missing authorization decision must never become the widest one;
fail-open is a choice a caller makes explicitly, never a default.

## The wire, briefly

The identity travels with the OPEN frame the server sends to start a session:

```
[1B idLen][idLen session id][2B rows][2B cols][identity JSON…]
```

The window size stays the first four bytes after the session id, so a device that
predates the identity block reads the size it always did and ignores the trailing
JSON — the frame grew a field without a protocol version. `PtyFrame.decodeOpen`
returns a null identity for that case, which the CLI reads as *unknown operator*.

## Adding a command

Nothing in `vyzorix` changes. A command is a symlink to `android-bin` (or
`app-tool`) beside it, and the front door discovers the set from the symlinks at
run time. To add an API:

1. add the handler in `android-core/.../bridge/apis/`;
2. register it in `ApiCatalogue.kt`;
3. add a row to `commands.txt` (the terse name) — `apis.txt` is generated.

The gate fails if the two files disagree, so a new API cannot ship without a
command. The server's `DeviceAPICatalog` is the other half: an API absent there is
unreachable to every role, which is why it mirrors the device's surface.

## See also

- `doc/ANDROID_SERVICE_ARCHITECTURE.md` — the container, the bridge, the token model.
- `VYZORIX/DOCUMENTATION/CONTAINER_TERMINAL.md` — the terminal relay the CLI runs inside.
- `VYZORIX/DOCUMENTATION/CONTAINER_ANDROID_APIS.md` — what a session may do.
