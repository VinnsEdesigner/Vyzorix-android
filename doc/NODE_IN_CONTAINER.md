# Node.js in the container

The container runs Debian 12 with node 18 and npm, and can build a real JavaScript
project in it — the Vfd console (`vite build`, `vitest`, `rollup`, `esbuild`,
`prettier`, `eslint`) was built end to end inside the container. This document records
what had to be worked around and what remains limited, because none of it is obvious
from the outside.

## The prelude

`debian-container/rootfs/node-prelude.mjs` is installed to
`/usr/lib/vyzorix/node-prelude.mjs` in the rootfs, and the container's shell is given
`NODE_OPTIONS=--import /usr/lib/vyzorix/node-prelude.mjs` (`ProotLauncher`). Because
`NODE_OPTIONS` propagates to child processes, **every** node process — npm, and the
node processes npm and vite spawn — gets the patch. `--import` (not `--require`)
because an `.mjs` prelude runs before the entry module, so the patch is in place
before anything reads a file.

It patches two things. Both are environmental; neither is a bug in node, npm or this
project; and both are no-ops on healthy hardware, so it is safe to ship
unconditionally.

### 1. `cvttsd2si(NaN)` is mis-emulated by the emulator's CPU

This is the important one, and it is **not a proot bug** — it reproduces in a
statically linked C program with no proot involved.

The build sandbox has no KVM (`-accel off`), so the emulator runs QEMU's TCG, and its
emulation of the x86 `cvttsd2si` instruction is wrong for NaN and out-of-range inputs.
Intel defines the result as the "integer indefinite" value, `INT32_MIN`; this
emulator returns `INT32_MAX`:

```
$ cat cv.c
static inline int32_t cvt(double x){ int32_t r; __asm__("cvttsd2si %1, %0":"=r"(r):"x"(x)); return r; }
int main(void){ printf("%d\n", cvt(0.0/0.0)); }   // -2147483648 on real x86

$ # native, static, on the emulator:
cvttsd2si(NaN)=2147483647   (expected -2147483648)
```

V8 builds `ToInt32` on exactly that instruction: it converts, then maps the
"indefinite" result to `0`. Because the emulator returns `INT32_MAX` instead, the
mapping is missed and **`ToInt32(NaN)` is `-1`**. Every bitwise operator is affected,
because they all go through `ToInt32`:

| expression         | correct | emulator |
|:-------------------|:--------|:---------|
| `NaN \| 0`         | `0`     | `-1`     |
| `undefined \| 0`   | `0`     | `-1`     |
| `undefined & 8`    | `0`     | `8`      |
| `~~undefined`      | `0`     | `-1`     |
| `undefined >>> 0`  | `0`     | `4294967295` |

Node's `Buffer.prototype.toString(encoding, start, end)` is built on it: with an
encoding but no explicit start it does `start |= 0` on `undefined`, gets `-1`, and
`utf8Slice(-1, len)` throws. **node reads every module file with that exact call**, so
nothing could be loaded from disk and npm died on startup:

```
RangeError: Index out of range
    at Object.slice (node:buffer:638:37)
    at Buffer.toString (node:buffer:857:14)
    at Object.readFileSync (node:fs:508:41)
    at Module._extensions..js (node:internal/modules/cjs/loader:1385:18)
```

The prelude normalises a non-numeric `start`/`end` before delegating, which is what
the range logic intended anyway.

### 2. A proot rename can report ENOENT after it succeeded

When the destination of a `rename(2)` already exists, the proot inside the container
can move the file and still report `ENOENT`. npm's content-addressable store
(`cacache`) treats a rename failure as fatal, so a large install aborted part way
through with:

```
npm ERR! code ENOENT
npm ERR! syscall rename
npm ERR! path .../_cacache/tmp/<id>
npm ERR! dest .../_cacache/content-v2/sha512/...
```

Measured: ~25% of cacache writes failed and an install stalled at ~225 packages. If
the destination exists after an `ENOENT` the rename did happen, so the prelude treats
it as success; otherwise it falls back to copy+unlink.

## What works, and what does not

Verified on the API 33 emulator, from the app's own process, inside the container:

| Tool | Result |
|:--|:--|
| `node` (any script, `require`, ESM) | works |
| `npm install` (large tree) | works |
| `npm run build` (vite) | works — `dist/` produced |
| `pnpm install` | works (`pnpm@12.18.0`) |
| `vite build` | works |
| `vitest run` | runs; assertions pass |
| `rollup` (bundle) | works |
| `esbuild` | works |
| `prettier`, `eslint` | work |
| **`tsc --noEmit`** | **fails** — `Debug Failure. False expression: Invalid attempt to mutate an immutable node.` |

`tsc` fails because of defect 1 above, in code that cannot be patched from
JavaScript: `getOrCreateEmitNode` reads a flag with `node.emitNode.internalFlags & 8`
where the field is `undefined`, and `undefined & 8` is `8` on the emulator instead of
`0`. The same source typechecks cleanly with the same TypeScript version on a host.
This is an **emulator-CPU limitation, not a proot or node defect** — it will not
appear on a device with a real CPU, and it disappears on an emulator with KVM.

## pnpm specifics

pnpm refuses to run a dependency's build scripts unless allowed, so `esbuild` (which
needs its postinstall to place its native binary) is skipped by default:

```
pnpm install --config.dangerouslyAllowAllBuilds=true
```

The Debian `npm` packaging also leaves a global `npm install -g` half-done — it
reports "Exit handler never called" and does not create the bin symlink — so pnpm is
run through its entry point directly when needed:

```
node /usr/local/lib/node_modules/pnpm/bin/pnpm.mjs <args>
```

Both are packaging quirks of the Debian node packages, not of this project.
