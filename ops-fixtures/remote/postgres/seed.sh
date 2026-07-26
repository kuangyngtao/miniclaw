#!/usr/bin/env bash
# M2-3/R1: Seed the fixture with deterministic business data.
# Idempotent — re-running resets to initial state.
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

echo "=== seed: resetting fixture to initial state ==="

# ── Drop and recreate ──
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS reconciliation_runs CASCADE;"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS orders CASCADE;"
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "DROP TABLE IF EXISTS accounts CASCADE;"

echo "Tables dropped."

# ── Re-run init scripts ──
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/001-schema.sql
echo "Schema (001) applied."

docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/002-multi-account.sql
echo "Multi-account (002) applied."

# ── Assert seed state ──
ACCT_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM accounts" | tr -d '[:space:]')
if [[ "$ACCT_COUNT" != "100" ]]; then
    echo "FATAL: expected 100 accounts, got $ACCT_COUNT" >&2
    exit 1
fi

ORDER_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM orders" | tr -d '[:space:]')
if [[ "$ORDER_COUNT" != "0" ]]; then
    echo "FATAL: expected 0 orders, got $ORDER_COUNT" >&2
    exit 1
fi

TOTAL_BALANCE=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT SUM(balance_cents) FROM accounts" | tr -d '[:space:]')
EXPECTED="10000000000"  # 100 accounts × 100000000 cents ($10,000 each)
if [[ "$TOTAL_BALANCE" != "$EXPECTED" ]]; then
    echo "FATAL: expected total balance $EXPECTED, got $TOTAL_BALANCE" >&2
    exit 1
fi

HOT_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM accounts WHERE account_class='HOT'" | tr -d '[:space:]')
if [[ "$HOT_COUNT" != "1" ]]; then
    echo "FATAL: expected 1 HOT account, got $HOT_COUNT" >&2
    exit 1
fi

echo "=== seed complete ==="
echo "Accounts: $ACCT_COUNT, Orders: $ORDER_COUNT, Balance: $TOTAL_BALANCE cents, HOT: $HOT_COUNT"
echo "All assertions passed."
