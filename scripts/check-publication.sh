#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
    echo "JDK 21 was not found. Set JAVA_HOME to a JDK 21 installation." >&2
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

host_arch="$(uname -m)"
if [[ "$host_arch" == "aarch64" || "$host_arch" == "arm64" ]]; then
    linux_suffix="linuxarm64"
    core_linux_publish=":kmpv:publishLinuxArm64PublicationToMavenLocal"
    render_linux_publish=":kmpv-render:publishLinuxArm64PublicationToMavenLocal"
    compose_linux_publish=":kmpv-compose:publishLinuxArm64PublicationToMavenLocal"
    cross_publish_tasks=()
else
    linux_suffix="linuxx64"
    core_linux_publish=":kmpv:publishLinuxX64PublicationToMavenLocal"
    render_linux_publish=":kmpv-render:publishLinuxX64PublicationToMavenLocal"
    compose_linux_publish=":kmpv-compose:publishLinuxX64PublicationToMavenLocal"
    cross_publish_tasks=(
        ":kmpv:publishLinuxArm64PublicationToMavenLocal"
        ":kmpv-render:publishLinuxArm64PublicationToMavenLocal"
        ":kmpv-compose:publishLinuxArm64PublicationToMavenLocal"
        ":kmpv:publishMingwX64PublicationToMavenLocal"
        ":kmpv-render:publishMingwX64PublicationToMavenLocal"
        ":kmpv-compose:publishMingwX64PublicationToMavenLocal"
    )
fi

publish_tasks=(
    "$core_linux_publish"
    "$render_linux_publish"
    "$compose_linux_publish"
    "${cross_publish_tasks[@]}"
)

android_enabled=false
if android_sdk="$(find_android_sdk)"; then
    export ANDROID_HOME="$android_sdk"
    export ANDROID_SDK_ROOT="$android_sdk"
    android_enabled=true
    publish_tasks+=(
        ":kmpv-android-jni:publishToMavenLocal"
        ":kmpv:publishAndroidPublicationToMavenLocal"
        ":kmpv-render:publishAndroidPublicationToMavenLocal"
        ":kmpv-compose:publishAndroidPublicationToMavenLocal"
    )
fi

"$root/gradlew" --no-daemon "${publish_tasks[@]}"

version="${KMPV_VERSION:-$(
    sed -n 's/^kmpv\.version=\(.*\)$/\1/p' "$root/gradle.properties" | head -n 1
)}"
[[ -n "$version" ]] || {
    echo "Could not determine the kmpv publication version." >&2
    exit 1
}
core="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-$linux_suffix/$version/kmpv-$linux_suffix-$version.klib"
render_pom="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-render-$linux_suffix/$version/kmpv-render-$linux_suffix-$version.pom"
compose_pom="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-compose-$linux_suffix/$version/kmpv-compose-$linux_suffix-$version.pom"

[[ -f "$core" ]] || { echo "Missing core Linux publication: $core" >&2; exit 1; }
[[ -f "$render_pom" ]] || { echo "Missing renderer Linux publication: $render_pom" >&2; exit 1; }
[[ -f "$compose_pom" ]] || { echo "Missing Compose Linux publication: $compose_pom" >&2; exit 1; }
grep -q "<artifactId>kmpv-$linux_suffix</artifactId>" "$render_pom" || {
    echo "Renderer publication does not depend on the core Linux publication." >&2
    exit 1
}
grep -q "<artifactId>kmpv-render-$linux_suffix</artifactId>" "$compose_pom" || {
    echo "Compose publication does not depend on the renderer Linux publication." >&2
    exit 1
}

if [[ "$host_arch" != "aarch64" && "$host_arch" != "arm64" ]]; then
    for suffix in linuxarm64 mingwx64; do
        for artifact in kmpv kmpv-render kmpv-compose; do
            published="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/$artifact-$suffix/$version"
            [[ -d "$published" ]] || {
                echo "Missing cross-target publication: $artifact-$suffix" >&2
                exit 1
            }
        done
    done
fi

if [[ "$android_enabled" == true ]]; then
    android_pom="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-android/$version/kmpv-android-$version.pom"
    jni_aar="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-android-jni/$version/kmpv-android-jni-$version.aar"
    android_render_pom="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-render-android/$version/kmpv-render-android-$version.pom"
    android_compose_pom="$HOME/.m2/repository/dev/brahmkshatriya/kmpv/kmpv-compose-android/$version/kmpv-compose-android-$version.pom"
    [[ -f "$android_pom" ]] || { echo "Missing Android kmpv publication: $android_pom" >&2; exit 1; }
    [[ -f "$jni_aar" ]] || { echo "Missing Android JNI publication: $jni_aar" >&2; exit 1; }
    [[ -f "$android_render_pom" ]] || { echo "Missing Android render publication: $android_render_pom" >&2; exit 1; }
    [[ -f "$android_compose_pom" ]] || { echo "Missing Android Compose publication: $android_compose_pom" >&2; exit 1; }
    grep -q '<artifactId>kmpv-android-jni</artifactId>' "$android_pom" || {
        echo "Android publication does not carry the JNI runtime dependency." >&2
        exit 1
    }
    grep -q '<artifactId>kmpv-android</artifactId>' "$android_render_pom" || {
        echo "Android render publication does not depend on Android kmpv." >&2
        exit 1
    }
    grep -q '<artifactId>kmpv-android-jni</artifactId>' "$android_render_pom" || {
        echo "Android render publication does not carry the JNI runtime dependency." >&2
        exit 1
    }
    grep -q '<artifactId>kmpv-render-android</artifactId>' "$android_compose_pom" || {
        echo "Android Compose publication does not depend on Android render." >&2
        exit 1
    }
fi

publication_label="kmpv + kmpv-render + kmpv-compose"
if [[ "$android_enabled" == true ]]; then
    publication_label+=" + Android"
fi
echo "Maven Local publications verified: $publication_label ($version)"
