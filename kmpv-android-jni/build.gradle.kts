plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "dev.kmpv.android.jni"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "kmpv-android-jni"
                pom {
                    name.set("kmpv-android-jni")
                    description.set("Android JNI carrier for the kmpv libmpv backend")
                }
            }
        }
    }
}
