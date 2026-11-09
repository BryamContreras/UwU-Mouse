plugins {
    id("com.android.library")
}

android {
    namespace = "dev.uwumouse.backend.host"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    defaultConfig { minSdk = uwu.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":injection-api"))
    implementation(project(":mapper-core"))
    implementation(project(":injection-runtime"))
    implementation(project(":capture-api"))
    implementation(project(":capture-native"))
    implementation(project(":window-context"))
}
