#!/bin/sh
set -eu
GRADLE_VERSION="9.5.0"
DIST="$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION-bin"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE_DIR="$HOME/.gradle/gold-intelligence"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
INSTALL="$CACHE_DIR/gradle-$GRADLE_VERSION"
mkdir -p "$CACHE_DIR"
if [ ! -x "$INSTALL/bin/gradle" ]; then
  if [ ! -f "$ZIP" ]; then
    curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  fi
  rm -rf "$INSTALL.tmp"
  mkdir -p "$INSTALL.tmp"
  unzip -q "$ZIP" -d "$INSTALL.tmp"
  mv "$INSTALL.tmp/gradle-$GRADLE_VERSION" "$INSTALL"
  rm -rf "$INSTALL.tmp"
fi
exec "$INSTALL/bin/gradle" "$@"
