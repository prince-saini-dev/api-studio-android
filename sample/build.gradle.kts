// A tiny app for checking the SDK on a device or emulator. Not part of the published artifact.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cloud.apistudio.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "cloud.apistudio.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Override with -PingestUrl=… / -PapiUrl=…; the defaults point at the host machine from an emulator.
        buildConfigField("String", "INGEST_URL", "\"${project.findProperty("ingestUrl") ?: "http://10.0.2.2:8787"}\"")
        buildConfigField("String", "API_URL", "\"${project.findProperty("apiUrl") ?: "http://10.0.2.2:8788"}\"")
        buildConfigField("String", "API_KEY", "\"${project.findProperty("apiKey") ?: "sample-key"}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    implementation(project(":api-studio-android"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
