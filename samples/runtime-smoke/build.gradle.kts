plugins {
    kotlin("multiplatform")
}

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureRuntimeSmoke() {
    binaries.executable {
        entryPoint = "dev.kmpv.smoke.main"
        val targetName = this@configureRuntimeSmoke.name
        val stem = targetName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
        val libraryDirs = providers.environmentVariable("KMPV_${stem}_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            .orEmpty()
        libraryDirs.forEach { linkerOpts("-L$it") }
        providers.environmentVariable("KMPV_${stem}_LINKER_OPTS").orNull
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
            ?.takeIf(List<String>::isNotEmpty)
            ?.let { linkerOpts(*it.toTypedArray()) }
    }
}

kotlin {
    mingwX64 { configureRuntimeSmoke() }
    macosX64 { configureRuntimeSmoke() }

    sourceSets {
        mingwX64Main.dependencies {
            implementation(project(":kmpv"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        macosX64Main.dependencies {
            implementation(project(":kmpv"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
    }
}
