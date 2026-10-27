// Root build. Modules declare their own plugins.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    base
}

tasks.register("checkAllModules") {
    group = "verification"
    description = "Runs check on every wired-in module."
    dependsOn(subprojects.map { "${it.path}:check" })
}
