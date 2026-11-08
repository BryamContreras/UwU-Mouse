plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "dev.mobimapper.mobimapper"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Stable identity used by the authorized backend and the independent laboratory.
        applicationId = "dev.mobimapper.mobimapper"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 34
        targetSdk = 36
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // CI restores the existing development certificate at an explicit path. Local builds
    // keep Android's normal debug configuration when this environment variable is absent.
    providers.environmentVariable("UWU_ANDROID_DEBUG_KEYSTORE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            val restoredKeystore = file(path)
            require(restoredKeystore.isFile) { "UWU_ANDROID_DEBUG_KEYSTORE must point to the restored development keystore" }
            signingConfigs.getByName("debug").storeFile = restoredKeystore
        }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Locally installable development release; distribution requires a separate key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        aidl = true
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(project(":mapper-core"))
    implementation(project(":injection-api"))
    implementation(project(":backend-host"))
    implementation(project(":presentation"))
    implementation(project(":activation-transport"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
