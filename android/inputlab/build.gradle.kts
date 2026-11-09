plugins {
    id("com.android.application")
}

android {
    namespace = "dev.uwumouse.inputlab"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.uwumouse.inputlab"
        minSdk = uwu.versions.minSdk.get().toInt()
        targetSdk = uwu.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // Development artifact only; never use this signing configuration for distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
