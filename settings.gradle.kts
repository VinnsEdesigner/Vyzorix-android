// Gradle module wiring. Layout is documented in doc/.

@Suppress("UnstableApiUsage")
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // libv, the vyzorix.v format. mavenLocal first so a developer who has run
        // `./gradlew publishToMavenLocal` in the libv checkout builds against their
        // working copy; JitPack is the published source for CI and other machines.
        mavenLocal()
        maven {
            url = uri("https://jitpack.io")
            // Scoped to the one group so nothing else resolves from JitPack. This is
            // JitPack's own group for the repo (`com.github.<owner>`), not the source
            // package and not a `com.github.<owner>.<repo>` path.
            content {
                includeGroup("com.github.VinnsEdesigner")
            }
        }
    }
}

rootProject.name = "Vyzorix-android"

include(":android-core")
project(":android-core").projectDir = file("android-core")

include(":cloud-connector")
project(":cloud-connector").projectDir = file("cloud-connector")

include(":app")
project(":app").projectDir = file("app")
