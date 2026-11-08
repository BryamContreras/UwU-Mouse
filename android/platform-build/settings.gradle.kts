pluginManagement {
    val toolchain = java.util.Properties().apply {
        file("../toolchain.properties").inputStream().use { load(it) }
    }
    resolutionStrategy {
        eachPlugin {
            val version = requested.version
            if (version != null) when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:$version")
                "org.jetbrains.kotlin.jvm" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$version")
            }
        }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version toolchain.getProperty("agp")
        id("com.android.library") version toolchain.getProperty("agp")
        id("org.jetbrains.kotlin.jvm") version toolchain.getProperty("kotlin")
    }
}

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

rootProject.name = "UwUMousePlatformValidation"

include(":host")
for (module in listOf(
    "mapper-core", "capture-api", "capture-native", "injection-api", "injection-runtime",
    "presentation", "activation-transport", "window-context", "backend-host", "inputlab",
)) {
    include(":$module")
    project(":$module").projectDir = file("../$module")
}

// Optional receive/draw experiment; no part of the installed production application.
if (providers.gradleProperty("includeLatencyProbe").orNull == "true") {
    include(":latencyprobe")
    project(":latencyprobe").projectDir = file("../latencyprobe")
}
