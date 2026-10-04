#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-}"

if [[ -z "$target" ]]; then
    echo "Usage: $0 <linuxX64|linuxArm64|mingwX64|macosX64|macosArm64|iosArm64>" >&2
    exit 2
fi

find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi
    for candidate in "$HOME"/.jdks/*; do
        if [[ -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | head -n 1 | grep -q '"21\.'; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "JDK 21 was not found. Set JAVA_HOME." >&2
    exit 1
}

export JAVA_HOME="$(find_jdk)"
export PATH="$JAVA_HOME/bin:$PATH"

find_android_sdk() {
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    return 1
}

# The project includes Android modules, so Gradle configuration needs an SDK
# even when this script is validating only a Native target.
if android_sdk="$(find_android_sdk)"; then
    export ANDROID_HOME="$android_sdk"
    export ANDROID_SDK_ROOT="$android_sdk"
fi

case "$target" in
    linuxX64)
        tasks=(
            :kmpv:linuxX64Test
            :samples:cli:linkDebugExecutableLinuxX64
            :kmpv-render:linuxX64Klib
            :kmpv-compose:linuxX64Klib
            :kmpv:publishLinuxX64PublicationToMavenLocal
            :kmpv-render:publishLinuxX64PublicationToMavenLocal
            :kmpv-compose:publishLinuxX64PublicationToMavenLocal
        )
        ;;
    linuxArm64)
        tasks=(
            :kmpv:linuxArm64Test
            :samples:cli:linkDebugExecutableLinuxArm64
            :kmpv-render:linuxArm64Klib
            :kmpv-compose:linuxArm64Klib
            :kmpv:publishLinuxArm64PublicationToMavenLocal
            :kmpv-render:publishLinuxArm64PublicationToMavenLocal
            :kmpv-compose:publishLinuxArm64PublicationToMavenLocal
        )
        ;;
    mingwX64)
        tasks=(
            :kmpv:mingwX64Test
            :samples:cli:linkDebugExecutableMingwX64
            :kmpv-render:mingwX64Klib
            :kmpv-compose:mingwX64Klib
            :kmpv:publishMingwX64PublicationToMavenLocal
            :kmpv-render:publishMingwX64PublicationToMavenLocal
            :kmpv-compose:publishMingwX64PublicationToMavenLocal
        )
        ;;
    macosX64)
        tasks=(
            :kmpv:macosX64Test
            :samples:cli:linkDebugExecutableMacosX64
            :kmpv-render:macosX64Klib
            :kmpv-compose:macosX64Klib
            :kmpv:publishMacosX64PublicationToMavenLocal
            :kmpv-render:publishMacosX64PublicationToMavenLocal
            :kmpv-compose:publishMacosX64PublicationToMavenLocal
        )
        ;;
    macosArm64)
        tasks=(
            :kmpv:macosArm64Test
            :samples:cli:linkDebugExecutableMacosArm64
            :kmpv-render:macosArm64Klib
            :kmpv-compose:macosArm64Klib
            :kmpv:publishMacosArm64PublicationToMavenLocal
            :kmpv-render:publishMacosArm64PublicationToMavenLocal
            :kmpv-compose:publishMacosArm64PublicationToMavenLocal
        )
        ;;
    iosArm64)
        tasks=(
            :kmpv:iosArm64MainKlibrary
            :kmpv:iosArm64TestBinaries
            :kmpv-render:iosArm64Klib
            :kmpv-compose:compileKotlinIosArm64
            :kmpv:publishIosArm64PublicationToMavenLocal
            :kmpv-render:publishIosArm64PublicationToMavenLocal
            :kmpv-compose:publishIosArm64PublicationToMavenLocal
        )
        ;;
    *)
        echo "Unsupported target: $target" >&2
        exit 2
        ;;
esac

echo "Validating kmpv target: $target"
exec "$root/gradlew" --no-daemon "${tasks[@]}" "${@:2}"
