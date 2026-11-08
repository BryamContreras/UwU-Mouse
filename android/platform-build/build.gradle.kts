import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

rootProject.layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("../../build/platform"))
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> { buildToolsVersion = "36.0.0" }
    }
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> { buildToolsVersion = "36.0.0" }
    }
}
