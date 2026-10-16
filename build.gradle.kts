// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dagger.hilt.android) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            // Pin kotlin-metadata-jvm to the project's Kotlin version to avoid transitive drift.
            // Note: kotlinx-metadata-jvm was removed in Kotlin 2.0 and is no longer needed.
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
        }
    }
}
