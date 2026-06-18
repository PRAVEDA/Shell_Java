#!/bin/sh
set -e

stty -icanon min 1 time 0 2>/dev/null || true
exec java --enable-native-access=ALL-UNNAMED --enable-preview -jar /tmp/codecrafters-build-shell-java/codecrafters-shell.jar "$@"