# AGENTS.md

Instructions for any agent or engineer working in this repository.

The architecture this repo implements is `doc/ANDROID_SERVICE_ARCHITECTURE.md`.
Read it before changing anything structural; it is the contract, and it records
*why* the awkward constraints below exist.

---

## What this is

A headless Android agent. It runs a Debian userspace in `proot`, exposes it over
a cloud WebSocket, and gives operators a terminal and (later) USB and app
control. The server half already exists in `vyzorix-update-server`; this repo is
the device half.

---

## Toolchain

Versions are pinned. Do not bump them casually — each has a reason recorded
below or in the doc.

| Component | Version | Notes |
|---|---|---|
| JDK | **21** | Debian trixie only ships 21. AGP 8.3.2 accepts 17–21. |
| Gradle | 8.7 (wrapper) | Committed. Always use `./gradlew`, never a system `gradle`. |
| AGP | 8.3.2 | In `gradle/libs.versions.toml`. |
| Kotlin | 1.9.23 | Must stay in lockstep with the KSP version below. |
| KSP | 1.9.23-1.0.20 | `<kotlin>-<ksp>`; a mismatch fails the build. |
| Android SDK | platform **34**, build-tools **34.0.0** | `compileSdk` is 34. |
| NDK | **26.3.11579264** | Pinned in `android-core/build.gradle.kts`. |
| CMake | 3.22.1 | Required by the NDK external native build. |
| `targetSdk` | **28** | See "The targetSdk rule" below. Not negotiable. |

### Installation (fresh machine)

Debian/Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk-headless unzip curl

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

# Command-line tools (the version here is the one validated in CI).
sudo mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -sSL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
sudo unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
sudo mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# Licenses must be accepted non-interactively, or sdkmanager stalls on a prompt
# and installs nothing. This is the single most common setup failure.
sudo mkdir -p "$ANDROID_HOME/licenses"
printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n84831b9409646a918e30573bab4c9c91346d8abd\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' \
  | sudo tee "$ANDROID_HOME/licenses/android-sdk-license" >/dev/null

sdkmanager --sdk_root="$ANDROID_HOME" --install \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;26.3.11579264" "cmake;3.22.1"

# sdkmanager must own its directory, or it reports
# "Failed to read or create install properties file" and installs nothing.
sudo chown -R "$(id -un):$(id -gn)" "$ANDROID_HOME"
```

Then, in the repo:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
./gradlew :android-core:assembleDebug
```

`local.properties` is gitignored on purpose: `sdk.dir` is machine-specific.

### Build commands

```bash
./gradlew projects                    # verify module wiring
./gradlew :android-core:assembleDebug # build the core library
./gradlew :app:assembleDebug          # build the APK
./gradlew gate                        # THE GATE — run this before every commit
./gradlew :android-core:testDebugUnitTest
```

Native builds are slow the first time (CMake configures for 3 ABIs). Use
`-Pandroid.injected.build.abi=arm64-v8a` to build one ABI while iterating.

---

## The gate

`./gradlew gate` runs everything a change must pass. CI runs the same task.

| Step | Tool | Covers |
|---|---|---|
| `ktlintCheck` | ktlint 1.3.1 | Kotlin formatting, import order, unused imports |
| `detekt` | detekt 1.23.6 | Kotlin complexity, smells, potential bugs |
| `deadcode` | `scripts/deadcode_gate.py` | see below |
| `testDebugUnitTest` | JUnit/Robolectric | JVM tests |
| `nativeGate` | `scripts/native-gate.sh` | clang-format + clang-tidy (NDK 26.3) |

### Why a custom dead-code gate

detekt catches unused **private** members; ktlint catches unused **imports**.
Neither catches the cases that actually rot a library module, so
`scripts/deadcode_gate.py` covers:

| Kind | What it catches |
|---|---|
| `kotlin-unused-decl` | an internal declaration nothing references |
| `kotlin-unused-const` | a `val`/`var` nothing references |
| `kotlin-unused-param` | a parameter never read in its own body |
| `kotlin-test-only-decl` | referenced only from `tests/` — never runs in production |
| `native-unused-func` | a C++ function with no call site |
| `jni-orphan-decl` | a Kotlin `external fun` with no native implementation |
| `jni-orphan-symbol` | a native `JNIEXPORT` with no Kotlin declaration |

The last two matter most: a JNI name mismatch **does not fail the build** — it
fails at runtime with `UnsatisfiedLinkError`. Nothing else looks for it.

Two suppression files, both requiring a reason:

- `config/deadcode-allowlist.txt` — `kind:name  # reason`
- `config/deadcode-public-api.txt` — names that are the module's intended
  surface. A public declaration's callers live in other modules, so it is judged
  only when listed here.

Do not add to either file to silence a finding you have not understood.

### Why the native tools come from the NDK

`clang-format` and `clang-tidy` ship with NDK 26.3. Using those rather than a
separate install means the formatter's LLVM version matches the compiler's. A
formatter from a different release disagrees about line breaks and produces churn
with no benefit.

`clang-tidy` needs a compile database:

```bash
./gradlew -PexportCompileCommands=true :android-core:configureCMakeDebug
```

The gate tells you this if the database is missing.

### Tuning the linters

Both configs disable checks that flag C-API idioms rather than defects, and each
disabled check carries its reason inline (`.clang-tidy`, `detekt.yml`). Before
disabling another, ask whether the check is wrong or the code is. Most of the
time it is the code.

---

## Running on an emulator in this environment

**Read this before trying to boot anything.** This environment has **no KVM**
(no `/dev/kvm`, no `vmx`/`svm` CPU flags), so hardware acceleration is
impossible and a naive `emulator` invocation fails.

What works:

- **Use the `x86_64` system image.** It matches the host architecture and runs
  under pure software emulation.
- **The `arm64-v8a` image does not work here.** It fails immediately with
  `Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on
  x86_64 host`. This is a hard limitation, not a configuration problem.
- Pass `-accel off` explicitly. Expect a full boot to take **3–5 minutes**, and
  the first activity launch a further few seconds. That is normal here.

Validated setup:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# One-time: image + AVD.
sdkmanager --sdk_root="$ANDROID_HOME" --install "system-images;android-28;default;x86_64" "emulator"
echo "no" | avdmanager create avd -n x86test \
  -k "system-images;android-28;default;x86_64" -d pixel --force

# Boot (background it; it is slow).
emulator -avd x86test -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -accel off &

# Wait for boot.
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done

# Install and run.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.vyzorix.agent/.<Activity>
adb logcat -d | grep -i <your tag>
```

### What the emulator can and cannot tell you

The image is **Android 9, `userdebug`**. Two caveats that matter:

- **`userdebug` is more permissive than `user`.** A production device (the Nokia
  C22) is a `user` build with stricter SELinux policy. Passing here is strong
  evidence, not proof.
- **It is Android 9, not 13.** Policy tightened between them.

So: the emulator settles *logic* and *syscall* questions well. It does not
finally settle *policy* questions. Those need the real device.

**Verified on this emulator** (see `doc/PTY_SPIKE_RESULTS.md`): `/dev/ptmx` is
world-writable, an untrusted app can allocate a PTY, and `execve` of a binary in
app storage succeeds at `targetSdk 28` under SELinux Enforcing.

---

## The targetSdk rule

`targetSdk` is pinned at **28** and must not be raised.

Android 10 denies `execve()` on files inside an app's home directory for any app
targeting API 29+. The kernel enforces this through SELinux — `seapp_contexts`
maps `targetSdk >= 29` to `untrusted_app_29`, which lacks the exec rule
`untrusted_app_27` has. **There is no userspace workaround**, and proot cannot
fake it (proot rewrites path arguments; it cannot rewrite what the kernel
enforces). Termux pins 28 for the same reason.

Consequences, both accepted:

- **No Play Store distribution.** Play requires a recent API level. This ships
  by sideload.
- The app carries the "built for an older Android" install notice.

If you find yourself wanting to raise it, re-read the doc's §1.1 first.

---

## Conventions

### Names

Module namespaces are flat and distinct — `com.vyzorix` (core),
`com.vyzorix.connector`, and `com.vyzorix.agent` on the app only. Do not nest
everything under `.agent`.

File names must say what the file *is*. Avoid generic names (`main.go`,
`helpers.kt`, `Manager.kt`, `Utils.kt`). Prefer specific ones that a reader can
place without opening them:

- Good: `PtyBroker.kt`, `PtySessionRegistry.kt`, `FileDescriptorPasser.kt`,
  `DeviceBootHook.kt`, `pty_native.cpp`, `fd_pass_native.cpp`.
- Bad: `GatewayService.kt`, `BootReceiver.kt`, `main.go`, `frames.go`,
  `bridge.cpp` — these are Android's or Go's defaults, and they describe a
  category rather than a thing.

### Layers

`android-core` is the **only** module that touches Android APIs. Everything
below it (the Debian userspace, the supervisor) talks to it over a unix socket.
There is exactly **one** cloud WebSocket owner. See the doc's §2 for the
invariants.

### Native code

- C++17, `-Wall -Werror` in debug. Do not silence warnings; fix them.
- **No exceptions across the JNI boundary.** Every native entry point returns a
  value or an error code; Kotlin maps it to a sealed result type. A C++ exception
  escaping into the JVM is undefined behaviour.
- Every `JNIEXPORT` symbol must match its Kotlin class name exactly. A mismatch
  fails at *runtime* with `UnsatisfiedLinkError`, not at build time — this has
  already cost time once.
- `JNIEnv` is thread-local. Never cache it; get it from the `JavaVM` on each
  thread that needs it.

### fd ownership and threading

The classic bug in this area is closing a file descriptor while another thread is
reading from it. That does not error cleanly — it silently reassigns the fd
number to an unrelated file. Rules:

- **One reader per master fd.** The PTY master fd is read by exactly one thread.
- **Close is synchronized.** Closing a session must be safe while a reader is
  blocked in `read()`. Do not `close()` an fd another thread may be using;
  instead signal the session (a shutdown flag, plus `poll()` with a timeout, or a
  self-pipe) and let the owner close it.
- **Never use blocking `read()` on a PTY master** without a poll/timeout. A shell
  that produces no output will hang the caller forever — this was found the hard
  way in the PTY spike.
- **`waitpid` is not safe to call blocking** on a live shell. It never exits on
  its own. Use `WNOHANG` plus a deliberate kill.

### Tests

Tests live in the repo-root `tests/` tree, **not** alongside the sources:

```
tests/android-core/unit/kotlin/...          JVM/Robolectric, runs anywhere
tests/android-core/instrumented/kotlin/...  needs a device or emulator
```

Each module's `build.gradle.kts` points its `test` and `androidTest` source sets
at the matching directory, so module trees contain only shipping code. Add a new
module's tests under `tests/<module>/`.

- JVM/Robolectric unit tests are the default; they run anywhere.
- Anything touching real syscalls (PTY, USB, exec) cannot run on the JVM. Those
  go in `instrumented/` and are verified on the emulator or the C22.
- Do not claim a platform-behaviour change is verified unless it ran on the
  emulator or a device. Compiling is not verifying.

---

## Committing

- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`).
- Say *why* in the body when the change is non-obvious. A reader in six months
  needs the reason, not a restatement of the diff.
- Do not commit `local.properties`, `.gradle/`, or build output — they are
  gitignored; leave them that way.

---

## Where things live

```
android-core/        the OS-facing library (foreground service, cloud WS,
                     API bridge, fd capabilities)
cloud-connector/     endpoint constants + app-proxy relay
app/                 the APK: manifest, permissions, nothing else
debian-engine/       rootfs tarball, proot launcher, pty-agent, android-bin
app-runtime/         supervisor (C) and app-* CLI wrappers
doc/                 architecture, the current build contract, spike results
tests/               all tests, split per module (unit/ and instrumented/)
```
