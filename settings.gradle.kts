pluginManagement {
    val composeNativeVersion = providers.gradleProperty("kmpv.composeNativeVersion").get()
    plugins {
        id("dev.brahmkshatriya.compose") version composeNativeVersion
    }
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}

rootProject.name = "kmpv"

val appleOnly = gradle.startParameter.projectProperties["kmpv.appleOnly"]?.toBoolean() == true

include(":kmpv")
include(":kmpv-render")
include(":kmpv-compose")
include(":samples:cli")
include(":samples:material-demo")
include(":samples:runtime-smoke")

if (!appleOnly) {
    include(":kmpv-android-jni")
    include(":samples:android")
}
