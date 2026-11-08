plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mobimapper.latencyprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.mobimapper.latencyprobe"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-latency-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
    sourceSets.named("main") {
        // Compile the actual mapper recorder, without importing the mapper implementation.
        kotlin.srcDir("../mapper-core/src/main/kotlin")
        kotlin.include("dev/mobimapper/latencyprobe/**", "dev/mobimapper/mobimapper/session/InputLatencyProbe.kt")
    }
}
