#!/bin/sh
set -e

mvn -q -B package
mkdir -p /tmp/codecrafters-build-shell-java
cp target/codecrafters-shell.jar /tmp/codecrafters-build-shell-java/codecrafters-shell.jar