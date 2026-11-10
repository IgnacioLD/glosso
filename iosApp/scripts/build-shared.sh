#!/bin/sh
# Xcode "Build Shared.framework" phase: Gradle compiles the shared Kotlin code
# into Shared.framework for the SDK and configuration Xcode is building, puts it
# where FRAMEWORK_SEARCH_PATHS points and copies the Compose resources into the
# app bundle. Xcode passes the inputs as environment variables (CONFIGURATION,
# SDK_NAME, ARCHS, TARGET_BUILD_DIR, ...).
set -eu

# Xcode starts phases with a minimal environment, so find the JDK the Gradle
# build uses (21) when JAVA_HOME is not inherited from a shell.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_HOME=""
    for candidate in \
        "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
        /opt/homebrew/opt/openjdk@21 \
        /usr/local/opt/openjdk@21 \
        "$(/usr/libexec/java_home 2>/dev/null || true)"; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
    if [ -z "$JAVA_HOME" ]; then
        echo "error: no JDK found. Install JDK 21 or set JAVA_HOME (see docs/IOS-DEV.md)." >&2
        exit 1
    fi
    export JAVA_HOME
fi

cd "${SRCROOT}/.."
./gradlew --console=plain :shared:embedAndSignAppleFrameworkForXcode
