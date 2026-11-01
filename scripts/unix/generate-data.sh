#!/usr/bin/env bash
# Usage: scripts/unix/generate-data.sh [tiny|starter|full] [seed]
set -euo pipefail
SCALE="${1:-starter}"; SEED="${2:-20261022}"
[ -f target/budget-intelligence.jar ] || mvn -q -DskipTests package
java -Xmx3g -jar target/budget-intelligence.jar generate --scale "$SCALE" --seed "$SEED" --out "data/$SCALE"
java -Xmx3g -jar target/budget-intelligence.jar evaluate --data "data/$SCALE" > /dev/null
echo "Dataset written to data/$SCALE"
