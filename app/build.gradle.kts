// :app — the APK.

import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * The cloud base URL. Empty when unset, which makes the app run bridge-only.
 *
 * Read from a Gradle property first (so a local build can override it), then the
 * environment (so CI can), then empty.
 */
fun cloudBaseUrl(): String =
    (findProperty("cloudBaseUrl") as String?)
        ?: System.getenv("VYZORIX_CLOUD_URL")
        ?: ""

/**
 * The app's version, from the catalog. A Gradle property overrides it so a release
 * build can stamp a version without editing the catalog, which is what the release
 * tooling needs.
 */
fun appVersionCode(): Int =
    (findProperty("appVersionCode") as String?)?.toInt()
        ?: libs.versions.android.appVersionCode
            .get()
            .toInt()

fun appVersionName(): String =
    (findProperty("appVersionName") as String?)
        ?: libs.versions.android.appVersionName
            .get()

/**
 * The pinned server public key material, PEM. Empty when unset.
 *
 * A PEM has newlines, which cannot sit in a Gradle property, so it is supplied
 * with literal `\n` escapes. Those are kept as-is: the value goes into a Java
 * string literal via `buildConfigField`, and `\n` is exactly what a Java string
 * literal needs to become a newline at runtime. Unescaping here would put a real
 * newline into the generated Java source and break the build.
 *
 * **Rotation:** this value may hold more than one PEM block. The connector trusts
 * every block it finds, so a build that carries `[new, old]` keeps working while
 * the server switches to `new`; a later build drops `old`. See PinnedServerKeys.
 */
fun serverPublicKey(): String =
    (findProperty("serverPublicKey") as String?)
        ?: System.getenv("VYZORIX_SERVER_KEY")
        ?: ""

/**
 * Fails a release build that has no cloud configuration.
 *
 * The trust anchor is compiled in and there is no way to add one after the fact, so
 * a release built without it is a device that can never register and can never be
 * fixed in the field — it is sideloaded, with no store to push an update
 * through. Failing here turns that from a silent mis-build into a build error.
 *
 * Scoped to release tasks on purpose: debug builds (and `gate`) still work with no
 * cloud configuration, which is the deliberate bridge-only mode used for tests.
 */
fun requireCloudConfigForRelease() {
    val tasks = gradle.startParameter.taskNames
    val buildingRelease = tasks.any { it.contains("Release", ignoreCase = true) }
    if (!buildingRelease) {
        return
    }

    val missing =
        buildList {
            if (serverPublicKey().isEmpty()) {
                add("serverPublicKey (-PserverPublicKey=... or VYZORIX_SERVER_KEY)")
            }
            if (cloudBaseUrl().isEmpty()) {
                add("cloudBaseUrl (-PcloudBaseUrl=... or VYZORIX_CLOUD_URL)")
            }
        }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "A release build needs a cloud configuration; missing: ${missing.joinToString(
                "; ",
            )}. " +
                "The pinned server key is the device's only trust anchor and is compiled in — " +
                "there is no way to add one to a shipped APK.",
        )
    }
}

requireCloudConfigForRelease()

/**
 * The ABI whose rootfs is bundled in the APK.
 *
 * **A universal APK cannot carry per-ABI assets.** Android splits *native libraries*
 * by ABI (`jniLibs/<abi>/`), but `assets/` is shared — there is no `assets/<abi>/`
 * mechanism. So one rootfs goes in the APK and the rest are fetched.
 *
 * `arm64` is the one bundled, because it is the overwhelming majority of Android
 * devices in use. `armeabi-v7a` and `x86_64` are built and published to the release,
 * and a device that is not arm64 downloads its rootfs on first boot. Bundling all
 * three would put ~134 MB of rootfs in every APK for the benefit of a minority.
 *
 * Override with `-ProotfsAbi=...` to build an ABI-specific APK (an app bundle does
 * this properly; a plain universal APK needs this one knob).
 */
fun bundledRootfsAbi(): String =
    (findProperty("rootfsAbi") as String?) ?: System.getenv("VYZORIX_ROOTFS_ABI") ?: "arm64"

/**
 * The bundled rootfs tarball, or null when it has not been built.
 *
 * A missing tarball is not an error: the container is one feature, and a build without
 * it still produces a working app that runs bridge-only until the rootfs is fetched.
 * What is an error is a *present but unverifiable* tarball, which is why the
 * checksum below is read from the same `SHA256SUMS` the release carries.
 */
fun bundledRootfsFile(): File? =
    rootProject
        .file("debian-container/rootfs/dist/debian-core-${bundledRootfsAbi()}.tar.gz")
        .takeIf { it.isFile }

/**
 * The SHA-256 of the core rootfs.
 *
 * The rootfs is not bundled, so this is what the fetcher verifies the download against.
 * It is read from the build's `SHA256SUMS` when present, else from the `rootfsSha256`
 * property / `VYZORIX_ROOTFS_SHA256` env — a release build in a clean checkout (no
 * `dist/`) supplies it that way. An empty value disables the fetch rather than fetching
 * unverified, which is the fail-closed default.
 */
fun bundledRootfsSha256(): String =
    (findProperty("rootfsSha256") as String?)
        ?: System.getenv("VYZORIX_ROOTFS_SHA256")
        ?: sha256FromSums("debian-core-${bundledRootfsAbi()}.tar.gz")

/**
 * The SHA-256 of the **extended** tier, verified after download. Same sources as the
 * core sum: the build's `SHA256SUMS`, else the `rootfsExtendedSha256` property / env.
 */
fun extendedRootfsSha256(): String =
    (findProperty("rootfsExtendedSha256") as String?)
        ?: System.getenv("VYZORIX_ROOTFS_EXTENDED_SHA256")
        ?: sha256FromSums("debian-extended-${bundledRootfsAbi()}.tar.gz")

/** The first field of the SHA256SUMS line for [name], or empty. */
fun sha256FromSums(name: String): String {
    val sums = rootProject.file("debian-container/rootfs/dist/SHA256SUMS")
    if (!sums.isFile) return ""

    return sums
        .readLines()
        .firstOrNull { it.endsWith(name) }
        ?.substringBefore(' ')
        ?.trim()
        .orEmpty()
}

/**
 * The bundled Layer 3 archive for this build's ABI, or null when it has not been
 * built.
 *
 * Unlike the rootfs, this is always bundled: it is our own code, it is ~360 KB, and
 * a device with no network must still be able to run apps. A missing archive is not
 * an error — the app builds and runs without the supervisor, which is the state a
 * source checkout is in until `scripts/build-layer3.sh` has run.
 */
fun bundledLayer3File(): File? =
    rootProject
        .file("app-runtime/dist/layer3-${bundledRootfsAbi()}.tar.gz")
        .takeIf { it.isFile }

/**
 * The SHA-256 of the Layer 3 archive.
 *
 * Layer 3 is not bundled, so this is what the fetcher verifies the download against.
 * Computed from the local archive when the build has one (a source checkout that ran
 * `scripts/build-layer3.sh`), else supplied via the `layer3Sha256` property /
 * `VYZORIX_LAYER3_SHA256` env — a release build in a clean checkout has no archive.
 * Empty disables the fetch rather than fetching unverified.
 */
fun layer3Sha256(): String {
    (findProperty("layer3Sha256") as String?)?.let { return it }
    System.getenv("VYZORIX_LAYER3_SHA256")?.let { return it }

    val file = bundledLayer3File() ?: return ""

    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }

    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Where a non-arm64 device fetches its rootfs.
 *
 * The GitHub release that carries the tarballs. Kept as a build config so the URL is
 * one place, and so a fork can point at its own release without touching code.
 */
fun rootfsBaseUrl(): String =
    (findProperty("rootfsBaseUrl") as String?)
        ?: System.getenv("VYZORIX_ROOTFS_URL")
        // The PUBLIC assets repo, not this one.
        //
        // This repo is private, and GitHub serves a private repo's release assets only
        // to authenticated callers — so a device in a member of the public's hands
        // gets a 404. The tarballs are Debian packages, public upstream, so they live
        // in their own public repo and the code stays private.
        ?: "https://github.com/VinnsEdesigner/gz-_ASSETS/releases/latest/download"

/**
 * Whether the terminal may run a plain device shell when the container is
 * not installed. Default false: it is a real weakening, since that shell runs as the
 * app's uid. A development build turns it on to exercise the terminal before the
 * Debian rootfs ships.
 */
fun allowUncontainedShell(): Boolean =
    (
        (findProperty("allowUncontainedShell") as String?)
            ?: System.getenv("VYZORIX_ALLOW_UNCONTAINED_SHELL")
            ?: "false"
    ).toBoolean()

android {
    namespace = "com.vyzorix.agent"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.vyzorix.agent"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = appVersionCode()
        versionName = appVersionName()

        // Exposed to code as BuildConfig fields. AGP only generates VERSION_CODE /
        // VERSION_NAME into BuildConfig when a build type overrides them, so the
        // update checker reads these explicit fields instead of relying on that.
        buildConfigField("int", "VERSION_CODE", "${appVersionCode()}")
        buildConfigField("String", "VERSION_NAME", "\"${appVersionName()}\"")

        // Cloud configuration. Both default to empty, which is a valid state: a build
        // with no cloud runs over the bridge alone (see VyzorixApplication). Supply
        // them with -PcloudBaseUrl=... -PserverPublicKey=... or the env vars below.
        //
        // SERVER_PUBLIC_KEY is the pinned server signing key. It is the device's only
        // trust anchor for what the server sends, so it is compiled in rather than
        // fetched — a fetched trust anchor is no anchor at all.
        // Instrumented tests need a runner declared or none are discovered: the task
        // reports "Starting 0 tests" and passes, which looks green while proving
        // nothing.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl()}\"")
        buildConfigField("String", "SERVER_PUBLIC_KEY", "\"${serverPublicKey()}\"")

        // The container's userspace.
        //
        // ROOTFS_SHA256 is what the installer verifies the extracted tree against. It
        // is read from the build's own SHA256SUMS rather than recomputed, so the APK
        // and the published release cannot disagree about what the tarball is. Empty
        // when no rootfs is bundled, which the installer treats as "nothing to verify"
        // — the APK signature already covers a bundled asset.
        buildConfigField("String", "ROOTFS_ABI", "\"${bundledRootfsAbi()}\"")
        buildConfigField("String", "ROOTFS_SHA256", "\"${bundledRootfsSha256()}\"")
        // The extended tier's checksum. It is downloaded, so it is verified after
        // fetching; empty means the tier is unavailable, never unverified.
        buildConfigField("String", "ROOTFS_EXTENDED_SHA256", "\"${extendedRootfsSha256()}\"")
        // Where the device fetches its rootfs. The release that carries the tarballs.
        buildConfigField("String", "ROOTFS_BASE_URL", "\"${rootfsBaseUrl()}\"")
        // Layer 3 (the supervisor + the terse-command CLI). Fetched from the release,
        // verified against this checksum. Empty means the download path is unavailable,
        // never unverified.
        buildConfigField("String", "LAYER3_SHA256", "\"${layer3Sha256()}\"")
        // Allow the terminal to run a plain device shell when the container is
        // not installed. Off unless asked for, because that shell runs as the app's
        // uid and can read the app's private files, which the container exists to
        // prevent. Set with -PallowUncontainedShell=true or VYZORIX_ALLOW_UNCONTAINED_SHELL.
        buildConfigField("boolean", "ALLOW_UNCONTAINED_SHELL", "${allowUncontainedShell()}")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // The rootfs tarball is already gzip -9. Letting AAPT re-compress it costs
        // build time and shrinks nothing, and — the reason this matters — a re-packed
        // asset is no longer the file SHA256SUMS describes, so the installer's check
        // would fail on a perfectly good build. `noCompress` stores it verbatim.
        //
        // `l3` is here for a different reason: `Layer3Installer` marks the install with
        // the archive's **byte length**, read via `AssetManager.openFd`. A *compressed*
        // asset has no fd, so `openFd` throws and the length reads as -1 — a constant
        // that never changes, so a rebuilt layer3 never reinstalls on an app update.
        // Storing it uncompressed gives a real fd and a real length. It is itself gzip,
        // so nothing is saved by deflating it again.
        noCompress += listOf("tar.gz", "l3")
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
        getByName("release") {
            // Unsigned at this stage; signing lands with the distribution story
            // (sideload only — see the targetSdk note).
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Tests live in the repo-root tests/ tree, matching the other modules.
    //
    // The instrumented source set matters here and nowhere else: `:app` is the only
    // module that has the rootfs asset, so it is the only place the container's
    // terminal can be driven end to end. An `:android-core` instrumented test runs in
    // a different APK with no assets, so `ProotLauncher.isReady()` is false there and
    // the shell falls back to the uncontained one — which is why the container path had
    // no test until now.
    sourceSets {
        getByName("test") {
            java.setSrcDirs(listOf("../tests/app/unit/kotlin"))
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf("../tests/app/instrumented/kotlin"))
        }
    }

    // See android-core: compileSdk is well above minSdk, so an unguarded newer-API
    // call must fail the gate rather than the device.
    lint {
        abortOnError = true
        warningsAsErrors = false
        error += "NewApi"
        disable += "ObsoleteSdkInt"
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(project(":android-core"))
    implementation(project(":cloud-connector"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The container's end-to-end test lives here because the rootfs asset does. It
    // drives the real service, the real bridge and the real container, so it needs
    // the instrumented runner and nothing to fake with.
    // Robolectric for the shell-selection unit test: `isReady()` is three filesystem
    // checks against an Android context, so the test stages real files rather than
    // mocking. That needs an Android runtime on the JVM.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit4)
    // The OTA live test builds the production UpdateClient, which needs OkHttp. The
    // connector depends on it with `implementation`, so it is not on this classpath
    // transitively; the test names it directly rather than the connector widening its
    // API surface for a test.
    androidTestImplementation(libs.okhttp)
}

/**
 * The rootfs and Layer 3 are **not** bundled in the APK.
 *
 * They are published as separate release assets (`debian-core-<abi>.tar.gz`,
 * `debian-extended-<abi>.tar.gz`, `layer3-<abi>.tar.gz`) and fetched on first run, verified
 * against the checksums compiled in above. This keeps the APK small (no ~46 MB rootfs, no
 * ~10 MB layer3) and lets each be updated without a new APK. The `ROOTFS_*` / `LAYER3_*`
 * BuildConfig fields below are what the fetchers use; there is no staging step.
 */
