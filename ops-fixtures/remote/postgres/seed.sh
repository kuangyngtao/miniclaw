#!/usr/bin/env bash
# M2-3: Seed the fixture with deterministic business data.
# Idempotent — re-running resets to initial state.
set -euo pipefail

PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"

echo "=== seed: resetting fixture to initial state ==="

# Reset database: drop and recreate
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -c "
        DROP TABLE IF EXISTS reconciliation_runs CASCADE;
        DROP TABLE IF EXISTS orders CASCADE;
        DROP TABLE IF EXISTS accounts CASCADE;
    " 2>&1 || true

# Re-run init scripts
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/001-schema.sql 2>&1
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -f /docker-entrypoint-initdb.d/002-multi-account.sql 2>&1

# Verify seed
echo "Verifying seed..."
ACCT_COUNT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT COUNT(*) FROM accounts")
echo "Accounts: $ACCT_COUNT (expected: 100)"

TOTAL_BALANCE=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
    psql -U postgres -d clawkit -tAc "SELECT SUM(balance_cents) FROM accounts")
echo "Total balance: $TOTAL_BALANCE cents (expected: 10000000000)"

echo "=== seed complete ==="
