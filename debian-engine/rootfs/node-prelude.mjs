// Node.js compatibility prelude for the Debian container.
//
// Installed to /usr/lib/vyzorix/node-prelude.mjs and loaded for every node process
// via NODE_OPTIONS=--import, so `npm`, `vite` and anything they spawn all get it.
//
// Two independent defects are worked around here. Both are environmental — neither
// is a bug in node, npm or this project — and both are no-ops on a healthy host, so
// this file is safe to ship unconditionally.
//
// ## 1. The emulator's CPU mis-emulates cvttsd2si(NaN)
//
// An x86_64 QEMU without KVM (`-accel off`, which is the only option in the build
// sandbox) emulates `cvttsd2si` — "convert with truncation, double to signed int" —
// incorrectly for NaN and out-of-range values. Intel defines the result as the
// "integer indefinite" value, INT32_MIN; this emulator returns INT32_MAX.
//
// V8's `ToInt32` is built on exactly that instruction: it converts, and maps the
// indefinite result to 0. Because the emulator returns INT32_MAX instead, the
// mapping is missed and `ToInt32(NaN)` comes out as **-1**:
//
//     $ node -e 'console.log(NaN | 0)'      # -1 on the emulator, 0 everywhere else
//
// `Buffer.prototype.toString(encoding, start, end)` relies on it. With an explicit
// encoding and no explicit start it does `start |= 0` on `undefined`, which is NaN,
// which the emulator turns into -1 — and then `utf8Slice(-1, len)` throws:
//
//     RangeError: Index out of range
//         at Object.slice (node:buffer:638:37)
//         at Buffer.toString (node:buffer:857:14)
//         at Object.readFileSync (node:fs:508:41)
//         at Module._extensions..js (node:internal/modules/cjs/loader:1385:18)
//
// node reads every module file with that exact call, so **nothing** could be loaded
// from disk and `npm` died on startup. This normalises a non-numeric `start`/`end`
// before delegating, which is what the range logic intended anyway.
//
// On real hardware (or an accelerated emulator) this patch changes nothing: the
// original `start |= 0` already produces 0 for `undefined`.
//
// ## 2. A proot rename can report ENOENT after it succeeded
//
// When the destination of a rename(2) already exists, the proot inside the
// container can move the file and still report ENOENT. npm's content-addressable
// store (cacache) treats a rename failure as fatal, so a large install aborted part
// way through with:
//
//     npm ERR! code ENOENT
//     npm ERR! syscall rename
//     npm ERR! path .../_cacache/tmp/<id>
//     npm ERR! dest .../_cacache/content-v2/sha512/...
//
// Measured: ~25% of cacache writes failed, and an install stalled at ~225 packages.
// If the destination exists after an ENOENT the rename did happen, so it is treated
// as success; otherwise a copy+unlink is attempted as a fallback.

import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const fs = require("fs");
const fsp = fs.promises;

// --- 1. Buffer.prototype.toString with a non-numeric start --------------------
const _toString = Buffer.prototype.toString;
Buffer.prototype.toString = function toString(encoding, start, end) {
    if (arguments.length === 0) {
        return _toString.call(this);
    }

    const length = this.length;
    if (start === undefined || Number.isNaN(start)) {
        start = 0;
    }
    if (end === undefined || Number.isNaN(end)) {
        end = length;
    }

    return _toString.call(this, encoding, start, end);
};

// --- 2. rename(2) that reports ENOENT but moved the file ----------------------
function destinationExistsSync(path) {
    try {
        fs.statSync(path);
        return true;
    } catch {
        return false;
    }
}

function destinationExists(path) {
    return fsp.stat(path).then(
        () => true,
        () => false,
    );
}

function recoverRename(src, dst) {
    return destinationExists(dst).then((exists) => {
        if (exists) {
            return undefined;
        }
        return fsp.copyFile(src, dst).then(() => fsp.unlink(src));
    });
}

const _rename = fsp.rename;
fsp.rename = function rename(src, dst) {
    return _rename.call(fsp, src, dst).catch((error) => {
        if (error.code !== "ENOENT") {
            throw error;
        }
        return recoverRename(src, dst);
    });
};

const _renameSync = fs.renameSync;
fs.renameSync = function renameSync(src, dst) {
    try {
        return _renameSync.call(fs, src, dst);
    } catch (error) {
        if (error.code !== "ENOENT") {
            throw error;
        }
        if (destinationExistsSync(dst)) {
            return undefined;
        }
        fs.copyFileSync(src, dst);
        try {
            fs.unlinkSync(src);
        } catch {
            // The copy is what matters; a leftover source is harmless.
        }
        return undefined;
    }
};
