# Build 2 verification — on the emulator

Status: **pass for items 1–5, 8; items 6–7 proven down to the proot exec, which is
blocked on missing assets (not code).**
Environment: `x86_64`, `system-images;android-28;default;x86_64`, Android 9,
`userdebug`, SELinux **Enforcing**, `-accel off` (no KVM on the host). Same image
`doc/PTY_SPIKE_RESULTS.md` used, so the two are directly comparable.

This records the run that turns Build 2 from "compiles" into "proven". The
contract's own list (§5) is the checklist.

---

## The gate

`./gradlew gate` — green. ktlint, detekt, the dead-code gate, `testDebugUnitTest`
and the native gate (clang-format + clang-tidy from NDK 26.3) all pass.

## Instrumented suite

`./gradlew :android-core:connectedDebugAndroidTest` — **26 tests, 0 failures.**

| Test | Contract item |
|---|---|
| `VyzorixForegroundServiceInstrumentedTest.serviceStartsAndIsRunning` | 1 |
| `...serviceSurvivesAProcessKillAndComesBack` | 1 |
| `...theBridgeSocketIsCreatedByTheRunningService` | 1 |
| `AndroidSocketServerInstrumentedTest.socketIsBoundInTheFilesystemNamespace` | 2 |
| `...validTokenAuthenticatesAndAScopedCallAnswers` | 2 |
| `...connectionWithNoTokenIsClosedWithoutServingARequest` | 3 |
| `...badTokenIsRefused` | 3 |
| `...tokenScopedToBatteryIsRefusedLogcat` | 4 |
| `...unknownApiReportsUnknownApi` | 4 |
| `...malformedFrameClosesTheConnection` | 2 (the 1 MiB cap) |
| `...nativeBindCreatesAFilesystemNode` / `...RefusesAPathTooLongForSunPath` | 2 |
| `...eventIsPushedOnlyToAuthenticatedConnections` | — |
| `...twoConnectionsCanUseTheSameToken` | — |
| `PtyBrokerInstrumentedTest.closingASessionLeavesTheFdCountUnchanged` (50 sessions) | 5 |
| `...opensPtyAndRunsACommand` / `...executesFromAppStorage` | 6 (exec half) |
| `...fdPassingRoundTrips` | 3 |
| `...sessionCapIsEnforcedOnDevice` / `...badExecutableReportsExecFailed` / `...resizeAppliesToTheSession` | — |
| `PtyRoundTripInstrumentedTest.aShellRoundTripsOverTheSocket` | 7 |
| `...aCreditFrameIsAcceptedAndKeepsOutputFlowing` | 7 |
| `...outputIsScopedToTheOwningSession` | 7 (no cross-session leak) |
| `...theFrameShapeIsTheContractShape` | 7 |

### Item 1, the restart, in the platform's own words

The service is `exported="false"` (correctly), so it is started from the app's own
process via the launcher, then the process is killed and ActivityManager is
watched:

```
ActivityManager: Process com.vyzorix.agent (pid 3973) has died: fore TOP
ActivityManager: Scheduling restart of crashed service
                 com.vyzorix.agent/com.vyzorix.service.VyzorixForegroundService in 1000ms
ActivityManager: Start proc 4028:com.vyzorix.agent/u0a68
                 for service com.vyzorix.agent/com.vyzorix.service.VyzorixForegroundService
VyzorixBridge:  bridge listening on /data/user/0/com.vyzorix.agent/files/android.sock
VyzorixService: service started
```

That is `START_STICKY` doing its job: the process died, the service came back on a
new pid, and the socket was re-bound.

---

## Items 6 and 7 — what is proven and what is not

**Proven:** everything from the socket down. `PtyRoundTripInstrumentedTest` wires
the broker, the reader, the server and the credit protocol exactly as the service
does, and drives a real shell: input in, output out, flow control respected, output
scoped to the owning session.

**Not proven, and why:** the executable is `/system/bin/sh`, not proot. Neither
`libproot.so` nor the Debian rootfs exists anywhere in this repository — the
`debian-engine/{rootfs,overlay,pty-agent,android-bin}` directories are empty. That
packaging is Layer 2 work, and the contract's §1 puts "full Debian rootfs
packaging" explicitly **out** of Build 2's scope. So items 6–7 are blocked on an
asset that was never in the repo, not on Build 2's logic.

When the rootfs and proot are dropped in, the remaining step is
`ProotLauncher.loginShellRequest`, which is unit-tested for the exact command line
(token in the inner `env -i` environment, socket bound to `/tmp/android.sock`,
`-0` for fake root).

---

## Bugs this run found (all fixed)

Three real defects survived the JVM tests and only appeared on a device:

1. **`AndroidSocketServer.start()` never called `NativePty.loadLibrary()`.** The PTY
   paths load it through `PtyBroker`; the bridge's own path did not, so the first
   bind threw `UnsatisfiedLinkError: No implementation found for
   ...bindListenSocket`. The symbol was in the `.so`; it was never loaded.
2. **Android's `LocalSocket` raises `IOException("Try again")` on an SO_TIMEOUT,
   not `SocketTimeoutException`.** The server caught only the latter, so the idle
   pre-auth deadline and post-auth idle reads were treated as broken connections.
   Worse, `BridgeProtocol.readFully` lost any partial bytes on the exception, which
   would desynchronize the stream. Both fixed: the server recognises the timeout by
   message, and `readFully` only propagates a timeout when nothing was read,
   otherwise raising an `EOFException` that keeps the cause.
3. **The app had no launcher Activity**, so on a fresh install the service could
   never start (many OEM builds also block `BOOT_COMPLETED` until first launch).
   Added `LaunchActivity`.

## Caveats

- `userdebug`, Android 9. This settles logic and syscalls; it does **not** settle
  SELinux policy on a production `user` build. The Nokia C22 still needs to confirm.
- The reader/writer socket protocol has no in-repo client; the reference client is
  the Debian-side agent (a later build). The contract shape is asserted by
  `theFrameShapeIsTheContractShape`.
