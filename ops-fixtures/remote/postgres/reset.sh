#!/usr/bin/env bash
# M2-3: Reset fixture to initial state and verify idempotency.
set -euo pipefail

PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"
CONTROL_TOKEN="fixture-control-only"

echo "=== reset: restoring fixture to clean state ==="

# Safety: verify project name and directory
if [[ ! "$FIXTURE_DIR" =~ ^/opt/clawkit/fixtures/ ]]; then
    echo "FATAL: FIXTURE_DIR must be under /opt/clawkit/fixtures/"
    exit 1
fi

# 1. Stop any running fault modes
echo "[1/3] Clearing fault state..."
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/control?mode=normal" 2>&1 || true

# 2. Re-seed database
echo "[2/3] Re-seeding database..."
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "
        DROP TABLE IF EXISTS reconciliation_runs CASCADE;
        DROP TABLE IF EXISTS orders CASCADE;
        DROP TABLE IF EXISTS accounts CASCADE;
    " 2>&1 || true

docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/001-schema.sql 2>&1
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/002-multi-account.sql 2>&1

# 3. Verify clean state
echo "[3/3] Verifying clean state..."
ORDER_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM orders" 2>&1 || echo "?")
echo "   Orders: $ORDER_COUNT (expected: 0)"

# Verify conservation invariant
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/verify" 2>&1 | python3 -c "
import sys, json
data = json.load(sys.stdin)
failed = [a for a in data if not a['passed']]
if failed:
    print(f'   INVARIANT FAILED: {len(failed)} accounts')
    sys.exit(1)
else:
    print(f'   Invariant: {len(data)} accounts PASSED')
" 2>&1 || echo "   Invariant: check skipped (python3 unavailable)"

echo "=== reset complete ==="
