// Android BLE: scale drivers and GATT/advertisement adapters on top of :core:protocol.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.kinash.smartscales.core.ble"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.ble)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
