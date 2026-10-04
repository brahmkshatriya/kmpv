#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

failures=()

project_version="${KMPV_VERSION:-$(
    sed -n 's/^kmpv\.version=\(.*\)$/\1/p' "$root/gradle.properties" | head -n 1
)}"

if [[ -z "$project_version" ]]; then
    failures+=("Could not determine the project version from KMPV_VERSION or gradle.properties")
elif [[ "$project_version" == *-SNAPSHOT ]]; then
    failures+=("Project version is still $project_version; set a release version")
fi

if [[ ! -f "$root/LICENSE" && ! -f "$root/LICENSE.txt" && ! -f "$root/LICENSE.md" ]]; then
    failures+=("No LICENSE file exists at the project root")
fi

release_properties="$root/gradle/kmpv-release.properties"
if [[ ! -f "$release_properties" ]]; then
    failures+=("Missing gradle/kmpv-release.properties")
else
    required_release_properties=(
        projectName
        projectDescription
        projectUrl
        developerId
        developerName
        developerUrl
        licenseName
        licenseUrl
        scmConnection
        scmDeveloperConnection
    )
    for name in "${required_release_properties[@]}"; do
        if ! grep -Eq "^${name}=.+$" "$release_properties"; then
            failures+=("Release property $name is not configured")
        fi
    done
fi

if [[ -n "${KMPV_MAVEN_USERNAME:-}" && -z "${KMPV_MAVEN_PASSWORD:-}" ]]; then
    failures+=("KMPV_MAVEN_USERNAME is configured but KMPV_MAVEN_PASSWORD is not")
fi
if [[ -n "${KMPV_MAVEN_PASSWORD:-}" && -z "${KMPV_MAVEN_USERNAME:-}" ]]; then
    failures+=("KMPV_MAVEN_PASSWORD is configured but KMPV_MAVEN_USERNAME is not")
fi

if ((${#failures[@]})); then
    echo "kmpv is not release-ready:" >&2
    for failure in "${failures[@]}"; do
        echo "  - $failure" >&2
    done
    exit 1
fi

"$root/scripts/check.sh"
"$root/scripts/check-publication.sh"

echo "Release-readiness checks passed for $project_version."
