plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val flutterRoot = providers.gradleProperty("uwu.flutterRoot").orNull
    ?: providers.environmentVariable("FLUTTER_ROOT").orNull
    ?: error("Set FLUTTER_ROOT or -Puwu.flutterRoot to a cached Flutter SDK.")
val embeddingJar = file("$flutterRoot/bin/cache/artifacts/engine/android-arm64/flutter.jar")
require(embeddingJar.isFile) {
    "Flutter embedding cache not found. Set FLUTTER_ROOT or -Puwu.flutterRoot to a cached Flutter SDK."
}

android {
    namespace = "dev.mobimapper.mobimapper"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mobimapper.mobimapper.validation"
        minSdk = 34
        targetSdk = 36
        versionCode = 37
        versionName = "0.2.0-platform-validation"
        manifestPlaceholders["applicationName"] = "android.app.Application"
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("../../app/src/main/AndroidManifest.xml")
            java.srcDir("../../app/src/main/kotlin")
            java.srcDir("../../app/src/main/java")
            res.srcDir("../../app/src/main/res")
        }
        getByName("test").java.srcDir("../../app/src/test/kotlin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    for (module in listOf(
        "mapper-core", "injection-api", "backend-host", "presentation", "activation-transport",
    )) implementation(project(":$module"))
    implementation(files(embeddingJar))
    implementation("androidx.activity:activity:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
