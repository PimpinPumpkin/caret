#!/bin/sh
# Runs the unit tests. Robolectric needs Java 21 for Android SDK 36.
# CARET_LINK_TESTS=1 also checks links over the network.
# CARET_KNOWN_FAILURES=1 also runs tests that document known bugs and fail on purpose.
set -e
cd "$(dirname "$0")/.."
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
exec ./gradlew --console=plain testDebugUnitTest "$@"
