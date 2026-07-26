#!/usr/bin/env bash
# M2-3/R1: Run a specific fixture case.
# Usage: run-case.sh [lock-injected|hot-contention|normal]
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

CASE="${1:-normal}"
CONTROL_TOKEN="${CONTROL_TOKEN:-fixture-control-only}"

echo "=== run-case: $CASE ==="
echo "Project: $PROJECT"

case "$CASE" in
    normal)
        echo "Resetting to normal state..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=normal"
        ;;

    lock-injected)
        echo "Injecting LOCK_INJECTED_V1 (120s row lock on hot-0001)..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=lock"
        ;;

    hot-contention)
        echo "Starting HOT_ACCOUNT_CONTENTION_V1 reconciliation scheduler..."
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
            wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
            "http://localhost:8080/internal/control?mode=hot-contention&intervalMs=3000&holdMs=2000"
        ;;

    *)
        echo "Unknown case: $CASE" >&2
        echo "Valid cases: normal, lock-injected, hot-contention" >&2
        exit 1
        ;;
esac

echo "=== run-case $CASE accepted ==="
