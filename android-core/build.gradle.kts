// :android-core

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vyzorix"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        externalNativeBuild {
            cmake {
                // The capability layer is PTY and USB work; arm64 is the fleet
                // target, armeabi-v7a covers older handsets, x86_64 keeps
                // emulator builds working for CI.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                cppFlags += listOf(
                    "-std=c++17",
                    "-Wall",
                    "-Werror",
                    "-Wno-unused-parameter",
                    "-fvisibility=hidden",
                )
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O0", "-g", "-DVYZORIX_DEBUG=1")
                }
            }
        }
        release {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O2", "-DVYZORIX_DEBUG=0")
                }
            }
        }
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
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
