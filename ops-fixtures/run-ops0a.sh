#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPEAT=${1:-10}
OUTPUT=${2:-"$SCRIPT_DIR/reports"}

cd "$REPO_ROOT"
mvn -q -pl extensions/clawkit-ops-loop -am package -DskipTests
java -jar extensions/clawkit-ops-loop/target/clawkit-ops-loop-0.1.0-all.jar \
  --compose "$SCRIPT_DIR/app-down/compose.yaml" \
  --output "$OUTPUT" \
  --repeat "$REPEAT"
