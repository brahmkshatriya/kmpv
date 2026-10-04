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

android_tasks=()
if android_sdk="$(find_android_sdk)"; then
    export ANDROID_HOME="$android_sdk"
    export ANDROID_SDK_ROOT="$android_sdk"
    android_tasks=(
        ":kmpv-android-jni:assembleDebug"
        ":kmpv:assembleAndroidMain"
        ":kmpv:testAndroidHostTest"
        ":kmpv-render:assembleAndroidMain"
        ":kmpv-compose:assembleAndroidMain"
        ":samples:android:assembleDebug"
    )
fi

host_arch="$(uname -m)"
if [[ "$host_arch" == "aarch64" || "$host_arch" == "arm64" ]]; then
    host_test_task=":kmpv:linuxArm64Test"
    sample_link_task=":samples:cli:linkDebugExecutableLinuxArm64"
    extra_tasks=(":kmpv-compose:compileKotlinLinuxArm64")
    cross_tasks=()
else
    host_test_task=":kmpv:linuxX64Test"
    sample_link_task=":samples:cli:linkDebugExecutableLinuxX64"
    extra_tasks=(
        ":kmpv-compose:compileKotlinLinuxX64"
        ":samples:material-demo:linkDebugExecutableLinuxX64"
    )
    cross_tasks=(
        ":kmpv:linuxArm64Klib"
        ":kmpv-render:linuxArm64Klib"
        ":kmpv-compose:compileKotlinLinuxArm64"
        ":kmpv:mingwX64Klib"
        ":kmpv-render:mingwX64Klib"
        ":kmpv-compose:compileKotlinMingwX64"
    )
fi

exec "$root/gradlew" --no-daemon \
    ":kmpv:checkKotlinAbi" \
    ":kmpv-render:checkKotlinAbi" \
    ":kmpv-compose:checkKotlinAbi" \
    "$host_test_task" \
    "$sample_link_task" \
    "${extra_tasks[@]}" \
    "${cross_tasks[@]}" \
    "${android_tasks[@]}" \
    "$@"
