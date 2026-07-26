#!/usr/bin/env bash
# M2-3/R1: Shared safety guard for all remote fixture scripts.
# Source this at the top of every script.
# Usage: source "$(dirname "$0")/lib/safety-guard.sh"
set -euo pipefail

# ── 1. Must be root ──
if [[ "$(id -u)" -ne 0 ]]; then
    echo "FATAL: must run as root" >&2
    exit 1
fi

# ── 2. PROJECT must match allowlist ──
PROJECT="${CLAWKIT_OPS_PROJECT:-clawkit-ops-m2-3}"
if [[ ! "$PROJECT" =~ ^clawkit-ops-m2-[0-9]+$ ]] && [[ ! "$PROJECT" =~ ^clawkit-ops-[a-z0-9-]+$ ]]; then
    echo "FATAL: PROJECT must match 'clawkit-ops-*' pattern, got: $PROJECT" >&2
    exit 1
fi

# ── 3. FIXTURE_DIR must resolve to an exact allowed path ──
FIXTURE_DIR="${CLAWKIT_OPS_FIXTURE_DIR:-/opt/clawkit/fixtures/postgres-lock}"
FIXTURE_DIR="$(realpath -m "$FIXTURE_DIR")"
FIXTURE_ROOT="$(realpath -m /opt/clawkit/fixtures)"
if [[ "$FIXTURE_DIR" != "$FIXTURE_ROOT"/* ]] || [[ "$FIXTURE_DIR" == "$FIXTURE_ROOT" ]] || [[ "$FIXTURE_DIR" == "$FIXTURE_ROOT/" ]]; then
    echo "FATAL: FIXTURE_DIR must be a subdirectory of $FIXTURE_ROOT, got: $FIXTURE_DIR" >&2
    exit 1
fi
# Block traversal attempts
if [[ "$FIXTURE_DIR" =~ \.\. ]]; then
    echo "FATAL: path traversal detected in FIXTURE_DIR: $FIXTURE_DIR" >&2
    exit 1
fi

# ── 4. Compose file must exist and be root-owned ──
COMPOSE_FILE="${FIXTURE_DIR}/compose.yaml"
if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo "FATAL: compose file not found: $COMPOSE_FILE" >&2
    exit 1
fi
compose_owner="$(stat -c '%U' "$COMPOSE_FILE" 2>/dev/null || echo unknown)"
if [[ "$compose_owner" != "root" ]]; then
    echo "FATAL: compose file must be root-owned, got: $compose_owner" >&2
    exit 1
fi

# ── 5. Project name safety for destructive operations ──
assert_safe_to_destroy() {
    local label="${1:-resource}"
    if [[ ! "$PROJECT" =~ ^clawkit-ops- ]]; then
        echo "FATAL: refusing to destroy $label — PROJECT must match 'clawkit-ops-*'" >&2
        exit 1
    fi
    echo "SAFETY CHECK: project=$PROJECT dir=$FIXTURE_DIR compose=$COMPOSE_FILE"
}

# ── 6. Export normalized variables ──
export CLAWKIT_OPS_PROJECT="$PROJECT"
export CLAWKIT_OPS_FIXTURE_DIR="$FIXTURE_DIR"
