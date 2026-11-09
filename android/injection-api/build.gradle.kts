plugins {
    id("com.android.library")
}
android {
    namespace = "dev.uwumouse.injection.api"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    defaultConfig { minSdk = uwu.versions.minSdk.get().toInt() }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
