#!/bin/sh
# Gradle wrapper script for Unix
GRADLE_APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(dirname "$0")
APP_HOME=$(cd "$APP_HOME" && pwd)
GRADLE_HOME=""
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
