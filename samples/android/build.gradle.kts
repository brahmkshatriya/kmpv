plugins {
    id("com.android.application")
}

android {
    namespace = "dev.kmpv.sample.android"
    compileSdk = 37

    providers.environmentVariable("KMPV_ANDROID_RUNTIME_DIR").orNull
        ?.takeIf(String::isNotBlank)
        ?.let { runtimeDir ->
            sourceSets.getByName("main").jniLibs.srcDir(runtimeDir)
        }
    providers.environmentVariable("KMPV_ANDROID_RUNTIME_ASSETS_DIR").orNull
        ?.takeIf(String::isNotBlank)
        ?.let { runtimeAssetsDir ->
            sourceSets.getByName("main").assets.srcDir(runtimeAssetsDir)
        }

    defaultConfig {
        applicationId = "dev.kmpv.sample.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation(project(":kmpv-render"))
}
