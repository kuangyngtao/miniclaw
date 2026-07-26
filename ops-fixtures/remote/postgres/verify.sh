#!/usr/bin/env bash
# M2-3/R1: Verify business invariants, health, and observer security.
# Exits non-zero on ANY failure.
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

FAILURES=0
CONTROL_TOKEN="${CONTROL_TOKEN:-fixture-control-only}"

fatal() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
echo "=== verify: business invariants and security ==="
echo "Project: $PROJECT"

# ── 1. Conservation invariant (per-account) ──
echo "[1/6] Conservation invariant..."
INVARIANT=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- --header="X-Control-Token: $CONTROL_TOKEN" \
    "http://localhost:8080/internal/verify" 2>&1) || {
    fatal "invariant endpoint unreachable"
    echo "$INVARIANT"
}
FAILED_ACCOUNTS=$(echo "$INVARIANT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
failed = [a for a in data if not a['passed']]
print(len(failed))
for a in failed:
    print(f'  {a[\"accountId\"]}: drift={a[\"drift\"]}', file=sys.stderr)
" 2>&1) || { fatal "invariant parse failed: $INVARIANT"; }
if [[ "$FAILED_ACCOUNTS" != "0" ]]; then
    fatal "invariant failed for $FAILED_ACCOUNTS account(s)"
else
    echo "   Invariant: PASS (all accounts)"
fi

# ── 2. Service health ──
echo "[2/6] Service health..."
HEALTH=$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T order-api \
    wget -qO- "http://localhost:8080/ready" 2>&1) || { fatal "health check failed: $HEALTH"; }
if ! echo "$HEALTH" | grep -q '"UP"'; then
    fatal "order-api not UP: $HEALTH"
else
    echo "   Health: UP"
fi

# ── 3. Observer connectivity ──
echo "[3/6] Observer read-only connectivity..."
OBSERVER_URL="jdbc:postgresql://127.0.0.1:15432/clawkit"
PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "SELECT 1 AS connectivity_check" -t 2>&1 | grep -q "1" || {
    fatal "observer cannot connect"
}
echo "   Observer: connected"

# ── 4. Observer diagnostic SELECT must succeed ──
echo "[4/6] Observer diagnostic SELECT..."
PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "SELECT pid, state, wait_event_type, wait_event FROM pg_stat_activity WHERE state IS NOT NULL LIMIT 5" \
    -t 2>&1 | head -3
echo "   Diagnostic SELECT: OK"

# ── 5. Observer must NOT be able to write ──
echo "[5/6] Observer write rejection..."
# INSERT must fail
if PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "INSERT INTO accounts(account_id, balance_cents, opening_balance_cents, account_class) VALUES ('test-x', 1, 1, 'NORMAL')" 2>&1; then
    fatal "observer was able to INSERT — permission leak!"
else
    echo "   INSERT rejected: OK"
fi
# UPDATE must fail
if PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "UPDATE accounts SET balance_cents = 0" 2>&1; then
    fatal "observer was able to UPDATE — permission leak!"
else
    echo "   UPDATE rejected: OK"
fi
# DELETE must fail
if PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "DELETE FROM orders" 2>&1; then
    fatal "observer was able to DELETE — permission leak!"
else
    echo "   DELETE rejected: OK"
fi
# DDL must fail
if PGPASSWORD="fixture-observer-only" psql -h 127.0.0.1 -p 15432 -U clawkit_observer -d clawkit \
    -c "CREATE TABLE test_ddl(id int)" 2>&1; then
    fatal "observer was able to CREATE TABLE — permission leak!"
else
    echo "   DDL rejected: OK"
fi

# ── 6. opsro security (if applicable) ──
echo "[6/6] opsro access checks..."
if id opsro &>/dev/null; then
    # Docker socket not accessible
    if sudo -u opsro docker ps &>/dev/null 2>&1; then
        fatal "opsro can access Docker socket"
    else
        echo "   Docker socket: blocked"
    fi
    # No sudo
    if sudo -u opsro sudo -n true &>/dev/null 2>&1; then
        fatal "opsro has sudo access"
    else
        echo "   sudo: blocked"
    fi
else
    echo "   opsro user not present (skip)"
fi

# ── Final ──
if [[ "$FAILURES" -gt 0 ]]; then
    echo "=== verify FAILED: $FAILURES check(s) ==="
    exit 1
fi
echo "=== verify PASSED: all checks OK ==="
