pluginManagement {
    val toolchain = java.util.Properties().apply {
        file("../toolchain.properties").inputStream().use { load(it) }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version toolchain.getProperty("agp")
        id("org.jetbrains.kotlin.jvm") version toolchain.getProperty("kotlin")
    }
}

plugins { id("org.jetbrains.kotlin.jvm") apply false }
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs {
        create("uwu") {
            val toolchain = java.util.Properties().apply {
                file("../toolchain.properties").inputStream().use { load(it) }
            }
            toolchain.forEach { (key, value) -> version(key.toString(), value.toString()) }
        }
    }
}
rootProject.name = "UwUMouseLatencyProbe"
