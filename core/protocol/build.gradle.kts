// Pure Kotlin: Chipsea frame parsing, command building, V2 decryption. No Android dependencies.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Parsed frames are returned as :core:model types (Impedance, units)
    api(project(":core:model"))
    testImplementation(libs.junit)
}
