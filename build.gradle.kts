import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import java.util.Properties

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.3" apply false
    id("com.android.library") version "9.3.3" apply false
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.compose") version "1.13.0-alpha01" apply false
    id("dev.brahmkshatriya.compose") apply false
}

val releaseProperties = Properties().apply {
    rootProject.file("gradle/kmpv-release.properties").inputStream().use(::load)
}

fun releaseProperty(name: String): String = checkNotNull(releaseProperties.getProperty(name)) {
    "Missing release property '$name'"
}

allprojects {
    group = "dev.kmpv"
    version = providers.environmentVariable("KMPV_VERSION")
        .orElse(providers.gradleProperty("kmpv.version"))
        .get()
}

subprojects {
    plugins.withId("maven-publish") {
        val centralJavadocJar = tasks.register<Jar>("centralJavadocJar") {
            archiveClassifier.set("javadoc")
        }

        extensions.configure<PublishingExtension> {
            val releaseRepositoryUrl = providers.environmentVariable("KMPV_MAVEN_REPOSITORY_URL").orNull
            if (!releaseRepositoryUrl.isNullOrBlank()) {
                repositories {
                    maven {
                        name = "kmpvRelease"
                        url = uri(releaseRepositoryUrl)
                        val repositoryUsername =
                            providers.environmentVariable("KMPV_MAVEN_USERNAME").orNull
                        val repositoryPassword =
                            providers.environmentVariable("KMPV_MAVEN_PASSWORD").orNull
                        if (!repositoryUsername.isNullOrBlank() || !repositoryPassword.isNullOrBlank()) {
                            credentials {
                                username = repositoryUsername
                                password = repositoryPassword
                            }
                        }
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                artifact(centralJavadocJar)
                pom {
                    inceptionYear.set("2026")
                    url.set(
                        providers.environmentVariable("KMPV_POM_URL")
                            .orElse(releaseProperty("projectUrl")),
                    )

                    scm {
                        url.set(
                            providers.environmentVariable("KMPV_POM_SCM_URL")
                                .orElse(releaseProperty("projectUrl")),
                        )
                        connection.set(
                            providers.environmentVariable("KMPV_POM_SCM_CONNECTION")
                                .orElse(releaseProperty("scmConnection")),
                        )
                        developerConnection.set(
                            providers.environmentVariable("KMPV_POM_SCM_DEVELOPER_CONNECTION")
                                .orElse(releaseProperty("scmDeveloperConnection")),
                        )
                    }

                    licenses {
                        license {
                            name.set(
                                providers.environmentVariable("KMPV_POM_LICENSE_NAME")
                                    .orElse(releaseProperty("licenseName")),
                            )
                            url.set(
                                providers.environmentVariable("KMPV_POM_LICENSE_URL")
                                    .orElse(releaseProperty("licenseUrl")),
                            )
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set(
                                providers.environmentVariable("KMPV_POM_DEVELOPER_ID")
                                    .orElse(releaseProperty("developerId")),
                            )
                            name.set(
                                providers.environmentVariable("KMPV_POM_DEVELOPER_NAME")
                                    .orElse(releaseProperty("developerName")),
                            )
                            url.set(
                                providers.environmentVariable("KMPV_POM_DEVELOPER_URL")
                                    .orElse(releaseProperty("developerUrl")),
                            )
                        }
                    }
                }
            }
        }

        val signingKey = providers.environmentVariable("KMPV_SIGNING_KEY").orNull
        if (!signingKey.isNullOrBlank()) {
            pluginManager.apply("signing")
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(
                    signingKey,
                    providers.environmentVariable("KMPV_SIGNING_PASSWORD").orNull,
                )
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}
