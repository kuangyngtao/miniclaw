#!/usr/bin/env bash
# M2-3: Run a specific fixture case.
# Usage: run-case.sh [lock-injected|hot-contention|normal]
set -euo pipefail

CASE="${1:-normal}"
PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"
CONTROL_TOKEN="fixture-control-only"

echo "=== run-case: $CASE ==="
echo "Project: $PROJECT"

case "$CASE" in
    normal)
        echo "Resetting to normal state..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=normal" 2>&1
        ;;

    lock-injected)
        echo "Injecting LOCK_INJECTED_V1 (120s row lock on hot-0001)..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=lock" 2>&1
        ;;

    hot-contention)
        echo "Starting HOT_ACCOUNT_CONTENTION_V1 reconciliation scheduler..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=hot-contention&intervalMs=3000&holdMs=2000" 2>&1
        # Run k6 load in background
        echo "Starting k6 load (90s, 20 req/s)..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" run --rm -d \
            -e BASE_URL=http://gateway:8080 \
            k6 run /scripts/hot-contention.js 2>&1 || true
        ;;

    *)
        echo "Unknown case: $CASE"
        echo "Valid cases: normal, lock-injected, hot-contention"
        exit 1
        ;;
esac

echo "=== run-case $CASE accepted ==="
