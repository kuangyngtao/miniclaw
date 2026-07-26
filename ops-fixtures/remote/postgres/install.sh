#!/usr/bin/env bash
# M2-3/R1: Deploy PostgreSQL business fixture on remote server.
# MUST be run as root. Idempotent.
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

POSTGRES_PORT="${POSTGRES_PORT:-15432}"
GATEWAY_PORT="${GATEWAY_PORT:-18081}"

echo "=== install: PostgreSQL business fixture ==="
echo "Project: $PROJECT  Dir: $FIXTURE_DIR"

# ── Copy fixture files ──
SRC="$(cd "$(dirname "$0")/../.." && pwd)/postgres-lock"
mkdir -p "$FIXTURE_DIR"
for f in compose.yaml gateway.conf; do
    cp "$SRC/$f" "$FIXTURE_DIR/"
done
cp -r "$SRC/init" "$FIXTURE_DIR/"
cp -r "$SRC/k6" "$FIXTURE_DIR/"
cp -r "$SRC/order-api" "$FIXTURE_DIR/"
cp "$SRC/control-case.ps1" "$FIXTURE_DIR/" 2>/dev/null || true
cp "$SRC/run-hot-contention.ps1" "$FIXTURE_DIR/" 2>/dev/null || true
chown -R root:root "$FIXTURE_DIR"
chmod -R u=rwX,go= "$FIXTURE_DIR"

# ── Fix compose: bind PostgreSQL to 127.0.0.1 only ──
sed -i 's/ports: \["${POSTGRES_PORT:-15432}:5432"\]/ports: ["127.0.0.1:${POSTGRES_PORT:-15432}:5432"]/' \
    "$COMPOSE_FILE" 2>/dev/null || true

# ── Build OrderApi JAR ──
JAR_DIR="$FIXTURE_DIR/order-api"
if [[ -f "$JAR_DIR/pom.xml" ]]; then
    BUILD_START="$(date +%s)"
    echo "Building order-api..."
    (cd "$JAR_DIR" && mvn -B -ntp -q clean package -DskipTests)
    BUILD_END="$(date +%s)"
    # Verify JAR exists and was just built
    JAR_FILE="$JAR_DIR/target/postgres-lock-order-api-0.1.0-shaded.jar"
    if [[ ! -f "$JAR_FILE" ]]; then
        echo "FATAL: order-api JAR not found after build: $JAR_FILE" >&2
        exit 1
    fi
    JAR_MTIME="$(stat -c '%Y' "$JAR_FILE" 2>/dev/null || echo 0)"
    if [[ "$JAR_MTIME" -lt "$BUILD_START" ]]; then
        echo "FATAL: order-api JAR not updated by build (mtime=$JAR_MTIME < build_start=$BUILD_START)" >&2
        exit 1
    fi
    echo "order-api JAR built: $JAR_FILE ($(du -h "$JAR_FILE" | cut -f1))"
fi

# ── Create root-only MCP env file ──
MCP_ENV="/etc/clawkit/ops-mcp.env"
cat > "$MCP_ENV" <<EOF
# clawkit OPS MCP environment — root-only
CLAWKIT_OPS_PROFILE=POSTGRES_DIAGNOSIS_V1
CLAWKIT_OPS_DB_URL=jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/clawkit
CLAWKIT_OPS_DB_USER=clawkit_observer
CLAWKIT_OPS_DB_PASSWORD=fixture-observer-only
EOF
chown root:root "$MCP_ENV"
chmod 0600 "$MCP_ENV"

# ── Start fixture ──
export POSTGRES_PORT GATEWAY_PORT
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --build --wait postgres order-api gateway 2>&1

echo "=== install complete ==="
echo "PostgreSQL: 127.0.0.1:${POSTGRES_PORT}"
echo "Gateway:    localhost:${GATEWAY_PORT}"
echo "MCP env:    $MCP_ENV"
