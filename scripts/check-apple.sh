#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "check-apple.sh must run on macOS." >&2
    exit 1
fi

find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local candidate
        candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
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

build_tasks=(
    ":kmpv:macosX64Klib"
    ":kmpv:macosArm64Klib"
    ":kmpv:iosArm64Klib"
    ":kmpv-render:macosX64Klib"
    ":kmpv-render:macosArm64Klib"
    ":kmpv-render:iosArm64Klib"
    ":kmpv-compose:compileKotlinMacosX64"
    ":kmpv-compose:compileKotlinMacosArm64"
    ":kmpv-compose:compileKotlinIosArm64"
)

publish_tasks=(
    ":kmpv:publishMacosX64PublicationToMavenLocal"
    ":kmpv:publishMacosArm64PublicationToMavenLocal"
    ":kmpv:publishIosArm64PublicationToMavenLocal"
    ":kmpv-render:publishMacosX64PublicationToMavenLocal"
    ":kmpv-render:publishMacosArm64PublicationToMavenLocal"
    ":kmpv-render:publishIosArm64PublicationToMavenLocal"
    ":kmpv-compose:publishMacosX64PublicationToMavenLocal"
    ":kmpv-compose:publishMacosArm64PublicationToMavenLocal"
    ":kmpv-compose:publishIosArm64PublicationToMavenLocal"
)

"$root/gradlew" --no-daemon -Pkmpv.appleOnly=true "${build_tasks[@]}" "$@"
"$root/gradlew" --no-daemon -Pkmpv.appleOnly=true "${publish_tasks[@]}"

echo "Apple core/render/Compose compile and publication artifacts verified for macOS x64, macOS arm64, and iOS arm64."
echo "This gate does not link a final application against libmpv; validate runtime linkage separately with a real Apple libmpv binary."
