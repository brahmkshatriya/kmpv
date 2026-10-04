#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shard="${1:?Usage: publish-ci-shard.sh <common|linux|android|apple|apple-x64|apple-arm64> REPOSITORY VERSION}"
repository="${2:?Usage: publish-ci-shard.sh <common|linux|android|apple|apple-x64|apple-arm64> REPOSITORY VERSION}"
version="${3:?Usage: publish-ci-shard.sh <common|linux|android|apple|apple-x64|apple-arm64> REPOSITORY VERSION}"

if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid Maven version: $version" >&2
    exit 1
fi
if [[ "$version" == *SNAPSHOT* ]]; then
    echo "Isolated CI repositories must not use SNAPSHOT versions: $version" >&2
    exit 1
fi

mkdir -p "$repository"
repository="$(cd "$repository" && pwd)"
repository_uri="$(python3 - "$repository" <<'PY'
import sys
from pathlib import Path
print(Path(sys.argv[1]).resolve().as_uri())
PY
)"

export KMPV_VERSION="$version"
export KMPV_MAVEN_REPOSITORY_URL="$repository_uri"

gradle=("$root/gradlew" --no-daemon --stacktrace --no-configuration-cache)

case "$shard" in
    common)
        tasks=(
            :kmpv:publishKotlinMultiplatformPublicationToKmpvReleaseRepository
            :kmpv-render:publishKotlinMultiplatformPublicationToKmpvReleaseRepository
            :kmpv-compose:publishKotlinMultiplatformPublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" "${tasks[@]}"
        ;;
    linux)
        tasks=(
            :kmpv:publishLinuxX64PublicationToKmpvReleaseRepository
            :kmpv-render:publishLinuxX64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishLinuxX64PublicationToKmpvReleaseRepository
            :kmpv:publishLinuxArm64PublicationToKmpvReleaseRepository
            :kmpv-render:publishLinuxArm64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishLinuxArm64PublicationToKmpvReleaseRepository
            :kmpv:publishMingwX64PublicationToKmpvReleaseRepository
            :kmpv-render:publishMingwX64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishMingwX64PublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" "${tasks[@]}"
        ;;
    android)
        tasks=(
            :kmpv-android-jni:publishReleasePublicationToKmpvReleaseRepository
            :kmpv:publishAndroidPublicationToKmpvReleaseRepository
            :kmpv-render:publishAndroidPublicationToKmpvReleaseRepository
            :kmpv-compose:publishAndroidPublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" "${tasks[@]}"
        ;;
    apple)
        tasks=(
            :kmpv:publishMacosX64PublicationToKmpvReleaseRepository
            :kmpv-render:publishMacosX64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishMacosX64PublicationToKmpvReleaseRepository
            :kmpv:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv-render:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv:publishIosArm64PublicationToKmpvReleaseRepository
            :kmpv-render:publishIosArm64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishIosArm64PublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" -Pkmpv.appleOnly=true "${tasks[@]}"
        ;;
    apple-x64)
        tasks=(
            :kmpv:publishMacosX64PublicationToKmpvReleaseRepository
            :kmpv-render:publishMacosX64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishMacosX64PublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" -Pkmpv.appleOnly=true "${tasks[@]}"
        ;;
    apple-arm64)
        tasks=(
            :kmpv:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv-render:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishMacosArm64PublicationToKmpvReleaseRepository
            :kmpv:publishIosArm64PublicationToKmpvReleaseRepository
            :kmpv-render:publishIosArm64PublicationToKmpvReleaseRepository
            :kmpv-compose:publishIosArm64PublicationToKmpvReleaseRepository
        )
        "${gradle[@]}" -Pkmpv.appleOnly=true "${tasks[@]}"
        ;;
    *)
        echo "Unknown shard: $shard" >&2
        exit 2
        ;;
esac

if ! find "$repository/dev/kmpv" -type f -name '*.pom' -print -quit 2>/dev/null | grep -q .; then
    echo "Shard $shard produced no Maven POMs in $repository" >&2
    exit 1
fi

echo "Published kmpv $shard shard at $version to $repository"
