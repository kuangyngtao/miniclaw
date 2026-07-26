#!/usr/bin/env bash
# M2-3/R1: Destroy fixture — stop containers, remove volumes, clean up.
# MUST be run as root. Uses safety-guard assertions.
set -euo pipefail
source "$(dirname "$0")/lib/safety-guard.sh"

# Extra safety for destructive operation
assert_safe_to_destroy "fixture project $PROJECT"

echo "=== destroy: removing fixture ==="
echo "Project: $PROJECT"
echo "Fixture dir: $FIXTURE_DIR"
echo "Compose file: $COMPOSE_FILE"

# ── Verify compose file points to expected project before touching anything ──
if ! docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps --services 2>&1 | grep -q .; then
    echo "No running services found for project $PROJECT — skipping container stop."
else
    echo "[1/3] Stopping containers and removing volumes..."
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans 2>&1
fi

# ── Remove fixture directory ──
echo "[2/3] Removing fixture directory..."
if [[ -d "$FIXTURE_DIR" ]]; then
    rm -rf "$FIXTURE_DIR"
    echo "   Removed: $FIXTURE_DIR"
fi

# ── Remove MCP env file ──
echo "[3/3] Removing MCP environment..."
if [[ -f /etc/clawkit/ops-mcp.env ]]; then
    rm -f /etc/clawkit/ops-mcp.env
    echo "   Removed: /etc/clawkit/ops-mcp.env"
fi

echo "=== destroy complete ==="
echo "Docker images preserved. Run 'docker image prune' to clean up."
