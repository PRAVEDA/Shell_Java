#!/bin/sh
set -e

mvn -q -B package
cp target/codecrafters-shell.jar /tmp/codecrafters-build-shell-java/codecrafters-shell.jar