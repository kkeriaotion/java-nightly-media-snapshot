#!/bin/sh
set -eu
BUILD_DIR="${TMPDIR:-/tmp}/media-nightly-snapshot-classes"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" src/main/java/dev/infrai/media/*.java
cp src/main/resources/application.properties "$BUILD_DIR/application.properties"
java -cp "$BUILD_DIR" dev.infrai.media.MediaSnapshotRunner "${1:-$(date +%F)}"
