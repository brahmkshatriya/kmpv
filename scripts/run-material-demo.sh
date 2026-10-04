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

exec "$root/gradlew" :samples:material-demo:runDebugExecutableLinuxX64 "$@"
