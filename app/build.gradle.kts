// :app — the APK.

import java.io.File

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
 * A missing tarball is not an error: the engine is one feature, and a build without
 * it still produces a working app that runs bridge-only until the rootfs is fetched.
 * What is an error is a *present but unverifiable* tarball, which is why the
 * checksum below is read from the same `SHA256SUMS` the release carries.
 */
fun bundledRootfsFile(): File? =
    rootProject
        .file("debian-engine/rootfs/dist/debian-core-${bundledRootfsAbi()}.tar.gz")
        .takeIf { it.isFile }

/**
 * The SHA-256 of the bundled rootfs, from the build's own `SHA256SUMS`.
 *
 * Read from the file the build wrote rather than recomputed here, so the APK's
 * expectation and the published checksum cannot disagree — they are the same line of
 * the same file. Empty when no rootfs is bundled, which the installer reads as
 * "nothing to verify against" (the APK signature already covers a bundled asset).
 */
fun bundledRootfsSha256(): String {
    val sums = rootProject.file("debian-engine/rootfs/dist/SHA256SUMS")
    val abi = bundledRootfsAbi()
    if (!sums.isFile) return ""

    return sums
        .readLines()
        .firstOrNull { it.endsWith("debian-core-$abi.tar.gz") }
        ?.substringBefore(' ')
        ?.trim()
        .orEmpty()
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
 * Whether the terminal may run a plain device shell when the container engine is
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
        versionCode = 1
        versionName = "0.1.0"

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

        // The container engine's userspace.
        //
        // ROOTFS_SHA256 is what the installer verifies the extracted tree against. It
        // is read from the build's own SHA256SUMS rather than recomputed, so the APK
        // and the published release cannot disagree about what the tarball is. Empty
        // when no rootfs is bundled, which the installer treats as "nothing to verify"
        // — the APK signature already covers a bundled asset.
        buildConfigField("String", "ROOTFS_ABI", "\"${bundledRootfsAbi()}\"")
        buildConfigField("String", "ROOTFS_SHA256", "\"${bundledRootfsSha256()}\"")
        // Where a device whose ABI is not the bundled one fetches its rootfs. The
        // release that carries all three tarballs.
        buildConfigField("String", "ROOTFS_BASE_URL", "\"${rootfsBaseUrl()}\"")
        buildConfigField("boolean", "ROOTFS_BUNDLED", "${bundledRootfsFile() != null}")
        // Allow the terminal to run a plain device shell when the container engine is
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
        noCompress += "tar.gz"
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
    // the shell falls back to the uncontained one — which is why the engine path had
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

    // The engine's end-to-end test lives here because the rootfs asset does. It
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
}

/**
 * Stages the bundled rootfs as an APK asset.
 *
 * ## Why a copy rather than pointing `assets.srcDirs` at the dist directory
 *
 * `dist/` holds six tarballs (three ABIs × core + extended) totalling ~450 MB. A
 * `srcDirs` entry would sweep every one of them into the APK. This copies the single
 * file the build is configured for, so the APK carries one rootfs.
 *
 * ## Why the asset path has no ABI in it
 *
 * `assets/` is shared across ABIs — Android splits only `jniLibs/`. So the asset is
 * always `rootfs/debian-core.tar.gz`, and `BuildConfig.ROOTFS_ABI` records which
 * architecture's userspace it is. A device of a different ABI downloads its own.
 *
 * A missing tarball is not an error. The engine is one feature; the app still builds
 * and runs bridge-only, which is the state a device is in until the rootfs arrives.
 */
val stageRootfs by tasks.registering {
    val source = bundledRootfsFile()
    val destDir = layout.buildDirectory.dir("generated/rootfsAssets/rootfs")
    val destName = "debian-core.rootfs"

    description = "Stages the ${bundledRootfsAbi()} rootfs into assets/."

    onlyIf { source != null }
    if (source != null) {
        inputs.file(source)
        outputs.file(destDir.map { it.file(destName) })

        doLast {
            // A plain byte copy, NOT Gradle's Copy task.
            //
            // The destination extension is `.rootfs`, not `.tar.gz`, and that is
            // load-bearing: AGP's mergeAssets treats a `.tar.gz` asset as an archive
            // and unpacks it, so the APK received a 157 MB *uncompressed* tar named
            // `debian-core.tar` instead of the 46 MB gzip the build produced and
            // SHA256SUMS describes. The installer's checksum check would then have
            // failed on a perfectly good build. An extension AGP does not recognise
            // as an archive is stored byte-for-byte.
            //
            // The bytes are still gzip; TarGz reads them as such. Only the name
            // differs, and the download path keeps the release's real `.tar.gz` name.
            val out = destDir.get().file(destName).asFile
            out.parentFile.mkdirs()
            source.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}

android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/rootfsAssets"),
)

tasks
    .matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(stageRootfs) }
