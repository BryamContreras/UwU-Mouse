plugins {
    id("com.android.application")
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
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    defaultConfig {
        applicationId = "dev.mobimapper.mobimapper.validation"
        minSdk = uwu.versions.minSdk.get().toInt()
        targetSdk = uwu.versions.targetSdk.get().toInt()
        versionCode = 37
        versionName = "0.2.0-platform-validation"
        manifestPlaceholders["applicationName"] = "android.app.Application"
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("../../app/src/main/AndroidManifest.xml")
            kotlin.directories.add("../../app/src/main/kotlin")
            java.srcDir("../../app/src/main/java")
            res.srcDir("../../app/src/main/res")
        }
        getByName("test").kotlin.directories.add("../../app/src/test/kotlin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
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
    implementation("androidx.activity:activity:${uwu.versions.androidxActivity.get()}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
