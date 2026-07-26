#!/usr/bin/env bash
# M2-3/R1: Reset fixture to initial state, verify idempotency.
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

echo "=== reset: restoring fixture to clean state ==="
echo "Project: $PROJECT  Dir: $FIXTURE_DIR"

# ── 1. Clear fault state ──
echo "[1/3] Clearing fault state..."
CONTROL_TOKEN="${CONTROL_TOKEN:-fixture-control-only}"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/control?mode=normal" 2>&1 || true
echo "   Fault state cleared."

# ── 2. Re-seed database ──
echo "[2/3] Re-seeding database..."
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS reconciliation_runs CASCADE;"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS orders CASCADE;"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS accounts CASCADE;"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/001-schema.sql
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/002-multi-account.sql

# ── 3. Assert clean state ──
echo "[3/3] Asserting clean state..."
ACCT_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM accounts" | tr -d '[:space:]')
ORDER_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM orders" | tr -d '[:space:]')

if [[ "$ACCT_COUNT" != "100" ]]; then
    echo "FATAL: expected 100 accounts, got $ACCT_COUNT" >&2
    exit 1
fi
if [[ "$ORDER_COUNT" != "0" ]]; then
    echo "FATAL: expected 0 orders, got $ORDER_COUNT" >&2
    exit 1
fi

# Verify invariant via order-api
INVARIANT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/verify" 2>&1)
FAILED=$(echo "$INVARIANT" | python3 -c "
import sys, json
print(sum(1 for a in json.load(sys.stdin) if not a['passed']))
" 2>&1) || FAILED="?"
if [[ "$FAILED" != "0" ]]; then
    echo "FATAL: invariant failed for $FAILED account(s)" >&2
    exit 1
fi

echo "=== reset complete ==="
echo "Accounts: $ACCT_COUNT, Orders: $ORDER_COUNT, Invariant: PASS"
