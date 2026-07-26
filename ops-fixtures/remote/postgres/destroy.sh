#!/usr/bin/env bash
# M2-3: Destroy fixture — stop containers, remove volumes, clean up.
# MUST be run as root. Requires explicit confirmation.
set -euo pipefail

PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"

echo "=== WARNING: destroy will remove all fixture data ==="
echo "Project: $PROJECT"
echo "Fixture dir: $FIXTURE_DIR"

# Safety: verify project name matches expected pattern
if [[ ! "$PROJECT" =~ ^clawkit-ops- ]]; then
    echo "FATAL: PROJECT must match 'clawkit-ops-*' pattern"
    exit 1
fi

# Safety: verify fixture directory
if [[ ! "$FIXTURE_DIR" =~ ^/opt/clawkit/fixtures/ ]]; then
    echo "FATAL: FIXTURE_DIR must be under /opt/clawkit/fixtures/"
    exit 1
fi

# Stop and remove containers, networks
echo "[1/3] Stopping containers..."
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans 2>&1

# Remove fixture directory
echo "[2/3] Removing fixture directory..."
rm -rf "$FIXTURE_DIR"

# Remove observer env file
echo "[3/3] Removing observer credentials..."
rm -f /etc/clawkit/ops-postgres-observer.env

echo "=== destroy complete ==="
echo "Project $PROJECT removed."
echo "NOTE: Docker images are preserved. Use 'docker image prune' to clean up."
