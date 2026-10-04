plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

val composeNativeVersion = providers.gradleProperty("kmpv.composeNativeVersion").get()
val officialComposeVersion = "1.13.0-alpha01"

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()

    android {
        namespace = "dev.kmpv.compose"
        compileSdk = 37
        minSdk = 24
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kmpv"))
            api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
        }

        desktopNativeMain.dependencies {
            api(project(":kmpv-render"))
            implementation("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
        }
        androidMain.dependencies {
            api(project(":kmpv-render"))
        }
        iosArm64Main.dependencies {
            api(project(":kmpv-render"))
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kmpv-compose")
            description.set("Thin Compose video-surface integration for kmpv")
        }
    }
}
