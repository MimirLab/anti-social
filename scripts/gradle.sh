#!/usr/bin/env bash
set -euo pipefail
export ANTISOCIAL_BUILD_ROOT="${ANTISOCIAL_BUILD_ROOT:-/run/media/red/RedtopStorage/DarwinBuild/LiberBuild}"
export JAVA_HOME="$ANTISOCIAL_BUILD_ROOT/jdk"
export ANDROID_HOME="$ANTISOCIAL_BUILD_ROOT/android-sdk"
export ANDROID_USER_HOME="$ANTISOCIAL_BUILD_ROOT/android-user"
export GRADLE_USER_HOME="$ANTISOCIAL_BUILD_ROOT/gradle-cache"
mkdir -p "$ANTISOCIAL_BUILD_ROOT/java-tmp"
mkdir -p "$ANTISOCIAL_BUILD_ROOT/kotlin-daemon"
export TMPDIR="$ANTISOCIAL_BUILD_ROOT/java-tmp"
# Kotlin 2.2.21 reads this property for its daemon registry; the older
# kotlin.daemon.runFilesPath spelling is ignored by this pinned compiler.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$ANTISOCIAL_BUILD_ROOT/java-tmp -Dkotlin.daemon.custom.run.files.path.for.tests=$ANTISOCIAL_BUILD_ROOT/kotlin-daemon"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Separate Gradle clients can recompile/delete shared Kotlin test classes while
# another test JVM is loading them. Keep each invocation's build/test interval
# exclusive. --close keeps the lock out of the long-lived Gradle daemon; frozen
# model audits can still run concurrently after their preparation task exits.
exec flock --exclusive --close "$ANTISOCIAL_BUILD_ROOT/gradle-invocation.lock" \
    "$ANTISOCIAL_BUILD_ROOT/gradle/bin/gradle" --project-cache-dir "$ANTISOCIAL_BUILD_ROOT/project-gradle-cache" -PantisocialBuildRoot="$ANTISOCIAL_BUILD_ROOT" "$@"
