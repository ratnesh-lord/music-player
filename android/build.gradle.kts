// AGP 9 provides Kotlin support itself ("built-in Kotlin"), so the Android module does not apply
// org.jetbrains.kotlin.android. Declaring the Kotlin plugins here pins the Kotlin Gradle Plugin
// version for the whole build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
