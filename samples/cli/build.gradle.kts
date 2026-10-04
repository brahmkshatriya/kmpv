plugins {
    kotlin("multiplatform")
}

fun libraryDirs(target: String): List<String> {
    val stem = target.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
    val configured = providers.environmentVariable("KMPV_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank).orEmpty()
    if (configured.isNotEmpty()) return configured
    if (target.startsWith("linux")) {
        return providers.environmentVariable("LINUX_NATIVE_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf("/usr/lib") }
    }
    return emptyList()
}

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureCli() {
    binaries.executable {
        entryPoint = "dev.kmpv.sample.main"
        val targetName = this@configureCli.name
        val stem = targetName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
        val opts = buildList {
            addAll(libraryDirs(targetName).map { "-L$it" })
            addAll(
                providers.environmentVariable("KMPV_${stem}_LINKER_OPTS").orNull
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.filter(String::isNotBlank)
                    .orEmpty(),
            )
        }.toTypedArray()
        if (opts.isNotEmpty()) linkerOpts(*opts)
    }
}

kotlin {
    linuxX64 { configureCli() }
    linuxArm64 { configureCli() }
    mingwX64 { configureCli() }
    macosX64 { configureCli() }
    macosArm64 { configureCli() }

    sourceSets {
        val desktopNativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":kmpv"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        linuxX64Main.get().dependsOn(desktopNativeMain)
        linuxArm64Main.get().dependsOn(desktopNativeMain)
        mingwX64Main.get().dependsOn(desktopNativeMain)
        macosX64Main.get().dependsOn(desktopNativeMain)
        macosArm64Main.get().dependsOn(desktopNativeMain)
    }
}
