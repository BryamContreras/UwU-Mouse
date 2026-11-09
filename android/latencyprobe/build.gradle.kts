plugins {
    id("com.android.application")
}

abstract class StageMapperProbe : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val destination = outputDirectory.file("dev/uwumouse/app/session/InputLatencyProbe.kt").get().asFile
        destination.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(destination, overwrite = true)
    }
}

// Compile only the real recorder, without importing the mapper implementation.
// AGP 9 built-in Kotlin accepts generated sources through the public variant API.
val stageMapperProbe = tasks.register<StageMapperProbe>("stageMapperProbe") {
    sourceFile.set(layout.projectDirectory.file("../mapper-core/src/main/kotlin/dev/uwumouse/app/session/InputLatencyProbe.kt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/mapper-probe/kotlin"))
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(stageMapperProbe, StageMapperProbe::outputDirectory)
}

android {
    namespace = "dev.uwumouse.latencyprobe"
    compileSdk = uwu.versions.compileSdk.get().toInt()
    buildToolsVersion = uwu.versions.buildTools.get()
    defaultConfig {
        applicationId = "dev.uwumouse.latencyprobe"
        minSdk = uwu.versions.minSdk.get().toInt()
        targetSdk = uwu.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-latency-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
