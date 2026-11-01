#!/usr/bin/env sh
# Lightweight Gradle bootstrapper. It downloads the pinned Gradle distribution
# when no system Gradle is installed. This avoids committing a binary wrapper JAR.
set -eu
GRADLE_VERSION="8.9"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/snapgpt-gradle/gradle-${GRADLE_VERSION}"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME_DIR")"
  ZIP_PATH="$(dirname "$GRADLE_HOME_DIR")/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$ZIP_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  else
    echo "curl or wget is required to download Gradle." >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required." >&2; exit 1; }
  unzip -q -o "$ZIP_PATH" -d "$(dirname "$GRADLE_HOME_DIR")"
fi
exec "$GRADLE_HOME_DIR/bin/gradle" "$@"
