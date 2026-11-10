plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // AGP 9 compiles Kotlin itself (no kotlin-android plugin). This plugin depends on
    // the Kotlin Gradle plugin at the catalog's Kotlin version, which lifts AGP's
    // bundled Kotlin to match it.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.roborazzi) apply false
}
