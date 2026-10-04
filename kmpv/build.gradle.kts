import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Locale

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

val appleOnly = providers.gradleProperty("kmpv.appleOnly").orNull?.toBoolean() == true

val bridgeSources = files(
    "src/nativeInterop/cinterop/kmpv_bridge.c",
    "src/nativeInterop/cinterop/include/kmpv_bridge.h",
    "src/nativeInterop/cinterop/include/kmpv_bridge_internal.h",
    "src/nativeInterop/cinterop/include/kmpv_mpv_abi.h",
)

fun nativeLibraryDirs(targetName: String): List<String> {
    val stem = targetName.environmentStem()
    val targetSpecific = providers.environmentVariable("KMPV_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)
        ?.filter(String::isNotBlank)
        .orEmpty()
    if (targetSpecific.isNotEmpty()) return targetSpecific
    if (targetName.startsWith("linux")) {
        return providers.environmentVariable("LINUX_NATIVE_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf("/usr/lib") }
    }
    return emptyList()
}

fun KotlinNativeTarget.configureNativeLibraryDirs() {
    val stem = name.environmentStem()
    val linkerOptions = buildList {
        addAll(nativeLibraryDirs(name).map { "-L$it" })
        addAll(
            providers.environmentVariable("KMPV_${stem}_LINKER_OPTS").orNull
                ?.commandParts()
                .orEmpty(),
        )
    }.toTypedArray()
    if (linkerOptions.isNotEmpty()) binaries.all { linkerOpts(*linkerOptions) }
}

private fun String.environmentStem(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase(Locale.US)

private fun String.commandParts(): List<String> =
    trim().split(Regex("\\s+")).filter(String::isNotBlank)

private fun konanDependency(name: String): File? =
    File(System.getProperty("user.home"), ".konan/dependencies/$name")
        .takeIf(File::isDirectory)

private fun konanLinuxClang(): File? =
    File(System.getProperty("user.home"), ".konan/dependencies")
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.matches(Regex("llvm-\\d+-x86_64-linux-essentials-.*")) }
        ?.map { it.resolve("bin/clang") }
        ?.filter(File::isFile)
        ?.maxByOrNull { it.parentFile.parentFile.name }

private fun konanLinuxLlvmAr(): File? =
    konanLinuxClang()?.parentFile?.resolve("llvm-ar")?.takeIf(File::isFile)

private fun mingwCrossCompiler(): List<String>? {
    val sysroot = konanDependency("msys2-mingw-w64-x86_64-2") ?: return null
    val clang = konanLinuxClang() ?: return null
    return listOf(
        clang.absolutePath,
        "--target=x86_64-w64-windows-gnu",
        "--sysroot=${sysroot.absolutePath}",
    )
}

private fun mingwCrossArchiver(): List<String>? =
    konanLinuxLlvmAr()?.let { listOf(it.absolutePath) }

private fun linuxArm64CrossToolchain(): File? =
    File(System.getProperty("user.home"), ".konan/dependencies")
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith("aarch64-unknown-linux-gnu-gcc-") }
        ?.maxByOrNull(File::getName)

private fun linuxArm64CrossCompiler(): List<String>? =
    linuxArm64CrossToolchain()
        ?.resolve("bin/aarch64-unknown-linux-gnu-gcc")
        ?.takeIf(File::isFile)
        ?.let { listOf(it.absolutePath) }

private fun linuxArm64CrossArchiver(): List<String>? =
    linuxArm64CrossToolchain()
        ?.resolve("bin/aarch64-unknown-linux-gnu-ar")
        ?.takeIf(File::isFile)
        ?.let { listOf(it.absolutePath) }

fun defaultCompiler(targetName: String): List<String>? {
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val hostIsArm64 = hostArch == "aarch64" || hostArch == "arm64"
    return when {
        targetName == "linuxX64" && hostOs.contains("linux") && !hostIsArm64 -> listOf("cc")
        targetName == "linuxArm64" && hostOs.contains("linux") && hostIsArm64 -> listOf("cc")
        targetName == "linuxArm64" && hostOs.contains("linux") && !hostIsArm64 -> linuxArm64CrossCompiler()
        targetName == "macosX64" && hostOs.contains("mac") ->
            listOf("xcrun", "--sdk", "macosx", "clang", "-arch", "x86_64")
        targetName == "macosArm64" && hostOs.contains("mac") ->
            listOf("xcrun", "--sdk", "macosx", "clang", "-arch", "arm64")
        targetName == "iosArm64" && hostOs.contains("mac") ->
            listOf("xcrun", "--sdk", "iphoneos", "clang", "-arch", "arm64")
        targetName == "mingwX64" && hostOs.contains("linux") -> mingwCrossCompiler()
        else -> null
    }
}

fun defaultArchiver(targetName: String): List<String>? {
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val hostIsArm64 = hostArch == "aarch64" || hostArch == "arm64"
    return when {
        targetName == "linuxArm64" && hostOs.contains("linux") && !hostIsArm64 -> linuxArm64CrossArchiver()
        targetName == "mingwX64" && hostOs.contains("linux") -> mingwCrossArchiver()
        defaultCompiler(targetName) != null -> listOf("ar")
        else -> null
    }
}

fun KotlinNativeTarget.configureKmpvInterop() {
    val targetName = name
    val stem = targetName.environmentStem()
    val targetBridgeDir = layout.buildDirectory.dir("native/bridge/$targetName")
    val targetObject = targetBridgeDir.map { it.file("kmpv_bridge.o") }
    val targetArchive = targetBridgeDir.map { it.file("libkmpv_bridge.a") }
    val taskSuffix = targetName.replaceFirstChar(Char::uppercaseChar)
    val configuredCompiler = providers.environmentVariable("KMPV_${stem}_CC")
    val configuredArchiver = providers.environmentVariable("KMPV_${stem}_AR")
    val configuredCInteropOpts = providers.environmentVariable("KMPV_${stem}_CINTEROP_OPTS")
    val compilerAvailable = configuredCompiler.isPresent || defaultCompiler(targetName) != null
    val archiverAvailable = configuredArchiver.isPresent || defaultArchiver(targetName) != null

    val compileBridge = tasks.register<Exec>("compileKmpvBridge$taskSuffix") {
        inputs.files(bridgeSources)
        outputs.file(targetObject)
        onlyIf("a compatible C compiler is configured for $targetName") { compilerAvailable }

        doFirst {
            targetBridgeDir.get().asFile.mkdirs()
            val compiler = configuredCompiler.orNull
                ?.commandParts()
                ?.takeIf(List<String>::isNotEmpty)
                ?: defaultCompiler(targetName)
                ?: throw GradleException(
                    "No C compiler configured for $targetName. " +
                        "Set KMPV_${stem}_CC and optionally KMPV_${stem}_CFLAGS.",
                )
            val extraFlags = providers.environmentVariable("KMPV_${stem}_CFLAGS").orNull
                ?.commandParts()
                .orEmpty()
            val flags = buildList {
                addAll(compiler)
                add("-std=c11")
                add("-O2")
                if (targetName != "mingwX64") add("-fPIC")
                add("-Isrc/nativeInterop/cinterop/include")
                addAll(extraFlags)
                add("-c")
                add("src/nativeInterop/cinterop/kmpv_bridge.c")
                add("-o")
                add(targetObject.get().asFile.absolutePath)
            }
            commandLine(flags)
        }
    }

    val archiveBridge = tasks.register<Exec>("archiveKmpvBridge$taskSuffix") {
        dependsOn(compileBridge)
        inputs.file(targetObject)
        outputs.file(targetArchive)
        onlyIf("a compatible C compiler and archiver are configured for $targetName") {
            compilerAvailable && archiverAvailable
        }

        doFirst {
            val archiver = configuredArchiver.orNull
                ?.commandParts()
                ?.takeIf(List<String>::isNotEmpty)
                ?: defaultArchiver(targetName)
                ?: throw GradleException(
                    "No static archiver configured for $targetName. Set KMPV_${stem}_AR.",
                )
            commandLine(archiver + listOf(
                "rcs",
                targetArchive.get().asFile.absolutePath,
                targetObject.get().asFile.absolutePath,
            ))
        }
    }

    compilations.getByName("main") {
        cinterops.create("kmpvBridge") {
            defFile(project.file("src/nativeInterop/cinterop/kmpv-bridge-$targetName.def"))
            compilerOpts(*configuredCInteropOpts.orNull?.commandParts().orEmpty().toTypedArray())
        }
    }

    tasks.matching { it.name == "cinteropKmpvBridge$taskSuffix" }.configureEach {
        dependsOn(archiveBridge)
        inputs.file(targetArchive)
        onlyIf("a compatible native bridge toolchain is configured for $targetName") {
            compilerAvailable && archiverAvailable
        }
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    // Linux deliberately links the system libmpv. No libmpv binary is bundled
    // into the KLIB or publication. Other Native targets also leave libmpv
    // runtime packaging to the consuming application/runtime artifact.
    linuxX64 {
        configureKmpvInterop()
        configureNativeLibraryDirs()
    }
    linuxArm64 {
        configureKmpvInterop()
        configureNativeLibraryDirs()
    }
    macosX64 { configureKmpvInterop(); configureNativeLibraryDirs() }
    macosArm64 { configureKmpvInterop(); configureNativeLibraryDirs() }
    mingwX64 { configureKmpvInterop(); configureNativeLibraryDirs() }
    iosArm64 { configureKmpvInterop(); configureNativeLibraryDirs() }

    android {
        namespace = "dev.kmpv"
        compileSdk = 37
        minSdk = 24
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        if (!appleOnly) {
            androidMain.dependencies {
                implementation(project(":kmpv-android-jni"))
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kmpv")
            description.set("Typed Kotlin Multiplatform bindings and player state for libmpv")
        }
    }
}
