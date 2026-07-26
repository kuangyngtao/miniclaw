#!/usr/bin/env bash
# M2-3: Deploy PostgreSQL business fixture on remote server.
# MUST be run as root. Idempotent — safe to re-run.
set -euo pipefail

PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"
POSTGRES_PORT="${POSTGRES_PORT:-15432}"
GATEWAY_PORT="${GATEWAY_PORT:-18081}"

echo "=== install: PostgreSQL business fixture ==="
echo "Project: $PROJECT"
echo "Fixture dir: $FIXTURE_DIR"

# Safety: verify target is a dedicated fixture directory
if [[ ! "$FIXTURE_DIR" =~ ^/opt/clawkit/fixtures/ ]]; then
    echo "FATAL: FIXTURE_DIR must be under /opt/clawkit/fixtures/"
    exit 1
fi

# Step 1: Create fixture directory
mkdir -p "$FIXTURE_DIR"

# Step 2: Copy fixture files (compose, init SQL, k6, gateway config)
SRC="$(cd "$(dirname "$0")/../.." && pwd)/postgres-lock"
cp "$SRC/compose.yaml" "$FIXTURE_DIR/"
cp "$SRC/gateway.conf" "$FIXTURE_DIR/"
cp -r "$SRC/init" "$FIXTURE_DIR/"
cp -r "$SRC/k6" "$FIXTURE_DIR/"
cp -r "$SRC/order-api" "$FIXTURE_DIR/"
cp "$SRC/control-case.ps1" "$FIXTURE_DIR/"
cp "$SRC/run-hot-contention.ps1" "$FIXTURE_DIR/"
chown -R root:root "$FIXTURE_DIR"
chmod -R u=rwX,go= "$FIXTURE_DIR"

# Step 3: Create root-only env file for observer credentials
OBSERVER_ENV="/etc/clawkit/ops-postgres-observer.env"
cat > "$OBSERVER_ENV" <<'EOF'
# PostgreSQL observer credentials — read-only diagnostic access only
# Managed by install.sh. Do not edit manually.
PGHOST=127.0.0.1
PGPORT=15432
PGDATABASE=clawkit
PGUSER=clawkit_observer
PGPASSWORD=fixture-observer-only
EOF
chown root:root "$OBSERVER_ENV"
chmod 0600 "$OBSERVER_ENV"

# Step 4: Build order-api JAR if needed
if [[ -f "$FIXTURE_DIR/order-api/pom.xml" ]]; then
    if command -v mvn &>/dev/null; then
        (cd "$FIXTURE_DIR/order-api" && mvn -B -ntp -q package -DskipTests) || true
    fi
fi

# Step 5: Start fixture
export POSTGRES_PORT GATEWAY_PORT
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --wait postgres order-api gateway 2>&1

echo "=== install complete ==="
echo "PostgreSQL: localhost:${POSTGRES_PORT}"
echo "Gateway:    localhost:${GATEWAY_PORT}"
echo "Observer env: $OBSERVER_ENV"
