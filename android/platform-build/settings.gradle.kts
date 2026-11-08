pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val version = requested.version
            if (version != null) when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:$version")
                "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$version")
            }
        }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.13.1"
        id("com.android.library") version "8.13.1"
        id("org.jetbrains.kotlin.android") version "2.2.20"
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
    }
}

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
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
