plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.mobimapper.activation.transport"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 34
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/adb/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    testImplementation("junit:junit:4.13.2")
}
