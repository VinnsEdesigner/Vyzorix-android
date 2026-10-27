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
    }
}

rootProject.name = "Vyzorix-android"

include(":android-core")
project(":android-core").projectDir = file("android-core")

include(":cloud-connector")
project(":cloud-connector").projectDir = file("cloud-connector")

include(":app")
project(":app").projectDir = file("app")
