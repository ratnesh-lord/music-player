import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: decision logic with no Android dependencies, so it is tested on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Shared fixtures used by both the Kotlin and the TypeScript tests.
    systemProperty("unpaused.testVectors", rootProject.projectDir.resolve("../shared/test-vectors").absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
