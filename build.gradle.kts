// Root build. Modules declare their own plugins.
//
// Linters are applied here, to every subproject, so a newly added module is
// linted the moment it exists. Forgetting to opt a module in is the usual way a
// gate quietly rots.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    base
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
}

subprojects {
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/detekt.yml"))
        parallel = true
    }
}

tasks.register("checkAllModules") {
    group = "verification"
    description = "Runs check on every wired-in module."
    dependsOn(subprojects.map { "${it.path}:check" })
}

// Dead-code gate: detekt covers unused private members, ktlint covers unused
// imports, and neither covers an unreferenced internal declaration, an unused
// parameter, or a Kotlin external fun with no native implementation. This does.
tasks.register<Exec>("deadcode") {
    group = "verification"
    description = "Fails on dead or unreferenced code."
    commandLine("python3", "$rootDir/scripts/deadcode_gate.py")
}

// Native gate: clang-format and clang-tidy, both from the NDK so the versions
// match the toolchain that compiles the code.
tasks.register<Exec>("nativeGate") {
    group = "verification"
    description = "Runs clang-format and clang-tidy over the native sources."
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "/opt/android-sdk")
    commandLine("bash", "$rootDir/scripts/native-gate.sh")
}

// The full gate. This is what CI runs and what a change must pass.
tasks.register("gate") {
    group = "verification"
    description =
        "Format, static analysis, Android lint, dead code, unit tests, and the native gate."
    dependsOn(
        subprojects.map { "${it.path}:ktlintCheck" },
        subprojects.map { "${it.path}:detekt" },
        subprojects.map { "${it.path}:lintDebug" },
        subprojects.map { "${it.path}:testDebugUnitTest" },
        "deadcode",
        "nativeGate",
    )
}
