// :cloud-connector

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vyzorix.connector"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        // Instrumented tests need a runner declared or none are discovered: the task
        // reports "Starting 0 tests" and passes, which looks green while proving
        // nothing.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // Tests live in the repo-root tests/ tree rather than alongside the sources,
    // matching the other modules.
    sourceSets {
        getByName("test") {
            java.setSrcDirs(listOf("../tests/cloud-connector/unit/kotlin"))
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf("../tests/cloud-connector/instrumented/kotlin"))
        }
    }
}

dependencies {
    // The core module owns the PTY broker and the bridge; the connector feeds both,
    // so it depends on it rather than duplicating either.
    api(project(":android-core"))

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // HTTP and the WebSocket. Hand-rolling TLS + WebSocket framing is where the
    // bugs live, and OkHttp is the one third-party dependency this build adds.
    implementation(libs.okhttp)

    // The vyzorix.v format, for verifying a delivered config. Published via JitPack
    // from VinnsEdesigner/libv; the parser and the canonicaliser must be the format's
    // own, or the device and the server disagree about what was signed.
    implementation(libs.libv)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
