// :app — the APK.

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
 * The pinned server public key, PEM. Empty when unset.
 *
 * A PEM has newlines, which cannot sit in a Gradle property, so it is supplied
 * with literal `\n` escapes. Those are kept as-is: the value goes into a Java
 * string literal via `buildConfigField`, and `\n` is exactly what a Java string
 * literal needs to become a newline at runtime. Unescaping here would put a real
 * newline into the generated Java source and break the build.
 */
fun serverPublicKey(): String =
    (findProperty("serverPublicKey") as String?)
        ?: System.getenv("VYZORIX_SERVER_KEY")
        ?: ""

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
        buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl()}\"")
        buildConfigField("String", "SERVER_PUBLIC_KEY", "\"${serverPublicKey()}\"")
    }

    buildFeatures {
        buildConfig = true
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
}
