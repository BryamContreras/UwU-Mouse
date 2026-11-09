plugins {
    id("com.android.library")
}

android {
    namespace = "dev.uwumouse.capture.nativeinput"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    ndkVersion = uwu.versions.ndk.get()
    defaultConfig {
        minSdk = uwu.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = uwu.versions.cmake.get() } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":capture-api"))
    testImplementation("junit:junit:4.13.2")
}
