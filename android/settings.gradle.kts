pluginManagement {
    val toolchain = java.util.Properties().apply {
        file("toolchain.properties").inputStream().use { load(it) }
    }
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version toolchain.getProperty("agp")
        id("com.android.library") version toolchain.getProperty("agp")
        id("org.jetbrains.kotlin.jvm") version toolchain.getProperty("kotlin")
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

dependencyResolutionManagement {
    versionCatalogs {
        create("uwu") {
            val toolchain = java.util.Properties().apply {
                file("toolchain.properties").inputStream().use { load(it) }
            }
            toolchain.forEach { (key, value) -> version(key.toString(), value.toString()) }
        }
    }
}

include(":app")
include(":inputlab")
include(":mapper-core", ":capture-api", ":capture-native", ":injection-api", ":injection-runtime", ":presentation")
include(":activation-transport", ":window-context", ":backend-host")
