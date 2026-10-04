#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="${1:?Usage: verify-kmpv-consumer.sh REPOSITORY VERSION}"
version="${2:?Usage: verify-kmpv-consumer.sh REPOSITORY VERSION}"
repository="$(cd "$repository" && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("${repository}") }
        mavenCentral()
        google()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}
rootProject.name = "kmpv-ci-consumer"
EOF

dependencies='''
            implementation("dev.kmpv:kmpv:'"$version"'")
            implementation("dev.kmpv:kmpv-render:'"$version"'")
            implementation("dev.kmpv:kmpv-compose:'"$version"'")'''

cat > "$tmp/build.gradle.kts" <<EOF
plugins {
    kotlin("multiplatform") version "2.4.20"
}
kotlin {
    linuxX64()
    sourceSets {
        commonMain.dependencies {
$dependencies
        }
    }
}
EOF

mkdir -p "$tmp/src/commonMain/kotlin"
cat > "$tmp/src/commonMain/kotlin/Smoke.kt" <<'EOF'
package smoke

import dev.kmpv.MpvPlayer

fun accepts(player: MpvPlayer): MpvPlayer = player
EOF

"$root/gradlew" --no-daemon --stacktrace --no-configuration-cache -p "$tmp" compileKotlinLinuxX64
echo "Verified external Gradle consumer resolution for kmpv $version"
