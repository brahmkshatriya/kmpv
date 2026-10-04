import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Locale

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

val appleOnly = providers.gradleProperty("kmpv.appleOnly").orNull?.toBoolean() == true

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
        defaultCompiler(targetName) == null -> null
        hostOs.contains("mac") -> listOf("xcrun", "ar")
        else -> listOf("ar")
    }
}

fun nativeLibraryDirs(targetName: String): List<String> {
    val stem = targetName.environmentStem()
    val targetSpecific = providers.environmentVariable("KMPV_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank).orEmpty()
    if (targetSpecific.isNotEmpty()) return targetSpecific
    if (targetName.startsWith("linux")) {
        return providers.environmentVariable("LINUX_NATIVE_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf("/usr/lib") }
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

fun KotlinNativeTarget.configureRenderInterop() {
    val targetName = name
    val stem = targetName.environmentStem()
    val targetDir = layout.buildDirectory.dir("native/render/$targetName")
    val targetObject = targetDir.map { it.file("kmpv_render_gl.o") }
    val targetArchive = targetDir.map { it.file("libkmpv_render_gl.a") }
    val suffix = targetName.replaceFirstChar(Char::uppercaseChar)
    val configuredCompiler = providers.environmentVariable("KMPV_${stem}_CC")
    val configuredArchiver = providers.environmentVariable("KMPV_${stem}_AR")
    val configuredCInteropOpts = providers.environmentVariable("KMPV_${stem}_CINTEROP_OPTS")
    val compilerAvailable = configuredCompiler.isPresent || defaultCompiler(targetName) != null
    val archiverAvailable = configuredArchiver.isPresent || defaultArchiver(targetName) != null

    val compileTask = tasks.register<Exec>("compileKmpvRenderGl$suffix") {
        inputs.files(
            "src/nativeInterop/cinterop/kmpv_render_gl.c",
            "src/nativeInterop/cinterop/include/kmpv_render_gl.h",
            "src/nativeInterop/cinterop/include/kmpv_render_mpv_abi.h",
        )
        outputs.file(targetObject)
        onlyIf("a compatible C compiler is configured for $targetName") { compilerAvailable }
        doFirst {
            targetDir.get().asFile.mkdirs()
            val compiler = configuredCompiler.orNull?.commandParts()?.takeIf { it.isNotEmpty() }
                ?: defaultCompiler(targetName)
                ?: error("No compiler configured for $targetName")
            val extraFlags = providers.environmentVariable("KMPV_${stem}_CFLAGS").orNull
                ?.commandParts().orEmpty()
            commandLine(buildList {
                addAll(compiler)
                add("-std=c11")
                add("-O2")
                if (targetName != "mingwX64") add("-fPIC")
                add("-Isrc/nativeInterop/cinterop/include")
                addAll(extraFlags)
                addAll(listOf("-c", "src/nativeInterop/cinterop/kmpv_render_gl.c", "-o", targetObject.get().asFile.absolutePath))
            })
        }
    }

    val archiveTask = tasks.register<Exec>("archiveKmpvRenderGl$suffix") {
        dependsOn(compileTask)
        inputs.file(targetObject)
        outputs.file(targetArchive)
        onlyIf("a compatible archiver is configured for $targetName") { compilerAvailable && archiverAvailable }
        doFirst {
            val archiver = configuredArchiver.orNull?.commandParts()?.takeIf { it.isNotEmpty() }
                ?: defaultArchiver(targetName)
                ?: error("No archiver configured for $targetName")
            commandLine(archiver + listOf("rcs", targetArchive.get().asFile.absolutePath, targetObject.get().asFile.absolutePath))
        }
    }

    compilations.getByName("main") {
        cinterops.create("kmpvRender") {
            defFile(project.file("src/nativeInterop/cinterop/kmpv-render-$targetName.def"))
            compilerOpts(*configuredCInteropOpts.orNull?.commandParts().orEmpty().toTypedArray())
        }
    }
    tasks.matching { it.name == "cinteropKmpvRender$suffix" }.configureEach {
        dependsOn(archiveTask)
        inputs.file(targetArchive)
        onlyIf("a compatible native render toolchain is configured for $targetName") {
            compilerAvailable && archiverAvailable
        }
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    linuxX64 {
        configureRenderInterop()
        configureNativeLibraryDirs()
    }
    linuxArm64 {
        configureRenderInterop()
        configureNativeLibraryDirs()
    }
    mingwX64 { configureRenderInterop(); configureNativeLibraryDirs() }
    macosX64 { configureRenderInterop(); configureNativeLibraryDirs() }
    macosArm64 { configureRenderInterop(); configureNativeLibraryDirs() }
    iosArm64 { configureRenderInterop(); configureNativeLibraryDirs() }

    android {
        namespace = "dev.kmpv.render"
        compileSdk = 37
        minSdk = 24
    }

    sourceSets {
        val openGlNativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":kmpv"))
            }
        }
        linuxX64Main.get().dependsOn(openGlNativeMain)
        linuxArm64Main.get().dependsOn(openGlNativeMain)
        mingwX64Main.get().dependsOn(openGlNativeMain)
        macosX64Main.get().dependsOn(openGlNativeMain)
        macosArm64Main.get().dependsOn(openGlNativeMain)
        iosArm64Main.get().dependsOn(openGlNativeMain)
        androidMain.dependencies {
            api(project(":kmpv"))
            if (!appleOnly) {
                implementation(project(":kmpv-android-jni"))
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kmpv-render")
            description.set("Optional platform video output integration for kmpv")
        }
    }
}
