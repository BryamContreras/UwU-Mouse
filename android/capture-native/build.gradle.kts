plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mobimapper.capture.nativeinput"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":capture-api"))
    testImplementation("junit:junit:4.13.2")
}
