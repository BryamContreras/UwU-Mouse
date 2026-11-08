plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mobimapper.backend.host"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    api(project(":injection-api"))
    implementation(project(":mapper-core"))
    implementation(project(":injection-runtime"))
    implementation(project(":capture-api"))
    implementation(project(":capture-native"))
    implementation(project(":window-context"))
}
