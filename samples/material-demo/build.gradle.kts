plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
}

val composeNativeVersion = "1.13.0-alpha09"

private fun String.environmentStem(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

private fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureDemoExecutable() {
    val targetName = name
    val stem = targetName.environmentStem()
    binaries.executable {
        entryPoint = "dev.kmpv.demo.main"
        val configuredDirs = providers.environmentVariable("KMPV_${stem}_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val libraryDirs = if (configuredDirs.isNotEmpty()) {
            configuredDirs
        } else if (targetName.startsWith("linux")) {
            providers.environmentVariable("LINUX_NATIVE_LIBRARY_DIRS").orNull
                ?.split(File.pathSeparatorChar)
                ?.filter(String::isNotBlank)
                .orEmpty()
                .ifEmpty { listOf("/usr/lib") }
        } else {
            emptyList()
        }
        libraryDirs.forEach { linkerOpts("-L$it") }
        if (targetName.startsWith("linux")) linkerOpts("-lEGL")
    }
}

kotlin {
    linuxX64 { configureDemoExecutable() }
    mingwX64 { configureDemoExecutable() }

    sourceSets {
        desktopNativeMain.dependencies {
            implementation(project(":kmpv-compose"))
            implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.material3:material3:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
    }
}

composeNativeApplication {
    applicationName.set("kmpv Material Demo")
    packageName.set("dev.kmpv.demo")
    executableName.set("kmpv-material-demo")
    packageVersion.set("0.1.0")
    description.set("Material OSC-inspired libmpv HLS demo")
    categories.set(listOf("AudioVideo", "Player"))
}
