#!/usr/bin/env bash
# M2-3: Verify business invariants and fixture health.
set -euo pipefail

PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"
CONTROL_TOKEN="fixture-control-only"

echo "=== verify: business invariants ==="

# 1. Invariant check via order-api
echo "[1/3] Conservation invariant..."
INVARIANT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/verify" 2>&1)
PASSED=$(echo "$INVARIANT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for a in d if a['passed']))" 2>/dev/null || echo "?")
FAILED=$(echo "$INVARIANT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for a in d if not a['passed']))" 2>/dev/null || echo "?")
echo "   Passed: $PASSED accounts, Failed: $FAILED accounts"

# 2. Health check
echo "[2/3] Service health..."
HEALTH=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- "http://localhost:8080/ready" 2>&1 || echo '{"status":"DOWN"}')
echo "   Order API: $HEALTH"

# 3. Database connectivity (read-only observer)
echo "[3/3] Observer read-only connectivity..."
OBSERVER_ENV="/etc/clawkit/ops-postgres-observer.env"
if [[ -f "$OBSERVER_ENV" ]]; then
    set -a; source "$OBSERVER_ENV"; set +a
    if PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -c "SELECT count(*) FROM pg_stat_activity" -t 2>&1; then
        echo "   Observer: connected"
    else
        echo "   Observer: FAILED"
    fi
fi

echo "=== verify complete ==="
