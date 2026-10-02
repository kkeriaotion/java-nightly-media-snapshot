#!/bin/sh
set -eu
BUILD_DIR="${TMPDIR:-/tmp}/media-nightly-snapshot-test-classes"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" src/main/java/dev/infrai/media/*.java src/test/java/dev/infrai/media/NightlyMediaSnapshotTest.java
java -cp "$BUILD_DIR" dev.infrai.media.NightlyMediaSnapshotTest
