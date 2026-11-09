plugins {
    id("com.android.library")
}
android {
    namespace = "dev.uwumouse.activation.transport"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    ndkVersion = uwu.versions.ndk.get()
    defaultConfig {
        minSdk = uwu.versions.minSdk.get().toInt()
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/adb/CMakeLists.txt"); version = uwu.versions.cmake.get() } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation("androidx.annotation:annotation:${uwu.versions.androidxAnnotation.get()}")
    implementation("org.conscrypt:conscrypt-android:${uwu.versions.conscrypt.get()}")
    implementation("org.bouncycastle:bcpkix-jdk18on:${uwu.versions.bouncyCastle.get()}")
    // The published non-LTO Prefab remains compatible with the selected NDK compiler.
    implementation("io.github.vvb2060.ndk:boringssl:${uwu.versions.boringSsl.get()}")
    testImplementation("junit:junit:4.13.2")
}
