# PTY spike — results

Status: **pass**, on emulator. Real-device confirmation still outstanding.

This records the experiment that de-risked the PTY pillar before any production
code was written. It answers the two questions the architecture depends on:

1. Can an untrusted Android app allocate a PTY at all (`/dev/ptmx` reachable
   under SELinux)?
2. Can it `execve` a binary from **app storage** at `targetSdk 28`?

Both are answered yes. The second is the one that justifies the `targetSdk 28`
pin, which was previously an argument from `seapp_contexts` rather than a
measurement.

---

## Environment

| | |
|---|---|
| Emulator | `x86_64`, `system-images;android-28;default;x86_64` |
| Android | 9 |
| Build type | `userdebug` |
| SELinux | **Enforcing** |
| Acceleration | none (`-accel off`, no KVM on the host) |
| App UID | 10067 (a normal untrusted app) |

---

## The two cases

The spike ran the doc §4.1 sequence twice: once exec'ing `/system/bin/sh`, once
exec'ing a script written into the app's own data directory. Both matter, and for
different reasons.

`/system/bin/sh` proves PTY allocation and the session/controlling-tty setup. It
does **not** prove the `targetSdk` argument, because `/system/bin/sh` lives in
the system partition and is executable regardless of `targetSdk`.

The app-storage case is the real test: it exercises the SELinux rule that
`targetSdk 28` buys.

### Case 1 — exec `/system/bin/sh`

```
uid=10067 euid=10067
OK posix_openpt master=38
OK grantpt
OK unlockpt
OK ptsname=/dev/pts/0
OK open slave=39
OK fork pid=3260
OK write master 25 bytes
OUT: :/ $
OUT: id; echo SPIKE_MARKER_OK
read 32 bytes, marker=YES
child still running (expected for a shell); sending SIGKILL
```

### Case 2 — exec a binary in app storage

The file was written to `dataDir`, `chmod +x`'d from Kotlin, then exec'd on the
slave end of a fresh PTY.

```
=== PTY sequence, exec=/data/user/0/com.vyzorix.ptyspike/exec_probe ===
uid=10067 euid=10067
OK posix_openpt master=38
OK grantpt
OK unlockpt
OK ptsname=/dev/pts/0
OK open slave=39
OK fork pid=3260
OK write master 25 bytes
OUT: hello-from-probe
OK child exited 0
```

`hello-from-probe` is the output of a script that exists only inside the app's
private data directory. **Exec from app storage works at `targetSdk 28` under
SELinux Enforcing.**

Also confirmed directly:

```
$ adb shell ls -laZ /dev/ptmx
crw-rw-rw- 1 root root u:object_r:ptmx_device:s0 5, 2 /dev/ptmx
```

`/dev/ptmx` is world-writable, so PTY allocation is not gated on a permission the
app cannot hold.

---

## Bugs found while running the spike

Both are traps the production broker must avoid, so they are recorded rather than
quietly fixed.

**1. JNI symbol name must match the class exactly.**
The C function was named `..._PtySpike_runPtySequence` while the Kotlin class was
`PtySpikeActivity`. The build succeeded; the app failed at runtime with
`UnsatisfiedLinkError`. There is no compile-time check for this.

**2. A blocking `read()` on a PTY master hangs forever.**
The first version read the master in a loop with no timeout. The child was an
interactive shell that produced output and then waited — so `read()` blocked
indefinitely and the app never finished. Fixed with `poll()` and a timeout.

**3. A blocking `waitpid()` on a live shell never returns.**
Related to the above. The shell does not exit on its own, so `waitpid(pid, ...)`
blocked after the I/O was done. Fixed with `WNOHANG`, then a deliberate
`SIGKILL` to reap.

All three are the reason `AGENTS.md` has a threading/fd-ownership section.

---

## What this does *not* prove

- **Android 13 behaviour.** This ran on Android 9. Policy tightened between them.
- **A `user` build.** The emulator is `userdebug`, which is more permissive in
  some paths. The Nokia C22 is a production `user` build with stricter policy.
- **proot.** The spike exec'd `/system/bin/sh` and a shell script — deliberately
  not proot, so a failure would isolate one variable. Whether proot itself
  launches from app storage is the next thing to prove.

## Outstanding

Re-run the same two cases on the Nokia C22 (Android 13, `user`) before treating
the PTY pillar as settled. The emulator makes a failure unlikely; it does not make
it impossible.
