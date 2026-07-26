#!/usr/bin/env bash
# revoke-opsro.sh — P0 remote read-only access revocation
#
# Design doc §6.4: removes opsro access without affecting fixture data.
# Order: disable public key → remove sudoers → remove launcher/gateway → lock user.
# Does NOT automatically restore docker group membership.
#
# Run as root. Idempotent.

set -euo pipefail

OPS_USER="opsro"
OPS_HOME="/home/${OPS_USER}"
AUTH_KEYS="${OPS_HOME}/.ssh/authorized_keys"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsro"
GATEWAY_DST="/usr/local/sbin/clawkit-ops-gateway"
LAUNCHER_DST="/usr/local/sbin/clawkit-ops-mcp-stdio"
JAR_DST="/usr/local/lib/clawkit/ops-mcp.jar"
ENV_FILE="/etc/clawkit/ops-mcp.env"
ERRORS=0

log()  { echo "  [OK] $*"; }
warn() { echo "  [WARN] $*" >&2; }
fail() { echo "  [FAIL] $*" >&2; ERRORS=$((ERRORS + 1)); }

if [[ "$(id -u)" -ne 0 ]]; then
    echo "FATAL: this script must run as root" >&2
    exit 1
fi

echo "=== Revoking OPS remote access ==="

# ── Step 1: Disable the public key (§6.4) ──
echo ""
echo "--- Step 1: Disable authorized key ---"
if [[ -f "${AUTH_KEYS}" ]]; then
    cp "${AUTH_KEYS}" "${AUTH_KEYS}.revoked.$(date +%s)"
    # Remove any line containing the gateway path (forced-command entries)
    grep -vF "${GATEWAY_DST}" "${AUTH_KEYS}.revoked.$(date +%s)" > "${AUTH_KEYS}" || true
    # If authorized_keys is now empty, remove it
    if [[ ! -s "${AUTH_KEYS}" ]]; then
        rm -f "${AUTH_KEYS}"
        log "removed authorized_keys (now empty)"
    else
        log "removed forced-command entries from authorized_keys"
    fi
else
    warn "authorized_keys not found — already revoked?"
fi

# ── Step 2: Remove sudoers grant (§6.4) ──
echo ""
echo "--- Step 2: Remove sudoers grant ---"
if [[ -f "${SUDOERS_FILE}" ]]; then
    rm -f "${SUDOERS_FILE}"
    log "removed sudoers file"
else
    log "sudoers file not found — already removed"
fi

# ── Step 3: Remove launcher and gateway (§6.4) ──
echo ""
echo "--- Step 3: Remove gateway/launcher ---"
for path in "${GATEWAY_DST}" "${LAUNCHER_DST}"; do
    if [[ -f "${path}" ]]; then
        rm -f "${path}"
        log "removed $(basename "${path}")"
    else
        log "$(basename "${path}") not found"
    fi
done

# ── Note: JAR and env file are NOT removed by default (§6.4) ──
# They are root-only and harmless without the gateway/sudoers chain.
# Remove explicitly if needed:
#   rm -f /usr/local/lib/clawkit/ops-mcp.jar
#   rm -f /etc/clawkit/ops-mcp.env

# ── Step 4: Lock or remove opsro user (§6.4) ──
echo ""
echo "--- Step 4: Lock opsro user ---"
if id "${OPS_USER}" &>/dev/null; then
    passwd -l "${OPS_USER}" >/dev/null 2>&1 || true
    # Expire the account to prevent ALL authentication
    usermod --expiredate 1 "${OPS_USER}" 2>/dev/null || true
    log "locked ${OPS_USER} (password locked + account expired)"
else
    log "${OPS_USER} user does not exist"
fi

# ── Step 5: Reload SSH (optional) ──
echo ""
echo "--- Step 5: Remove sshd_config Match block ---"
SSHD_CONFIG="/etc/ssh/sshd_config"
if [[ -f "${SSHD_CONFIG}" ]]; then
    if grep -qF "Match User opsro" "${SSHD_CONFIG}" 2>/dev/null; then
        cp "${SSHD_CONFIG}" "${SSHD_CONFIG}.bak.$(date +%s)"
        # Remove the Match block — lines between Match User opsro and next Match/EOF
        sed -i '/^Match User opsro$/,/^Match /{/^Match User opsro$/d;/^Match /!d;}' "${SSHD_CONFIG}" 2>/dev/null || true
        # Simpler approach: just comment out the clawkit block
        if grep -qF "Match User opsro" "${SSHD_CONFIG}"; then
            warn "could not auto-remove sshd_config Match block — edit manually"
        else
            log "removed sshd_config Match block"
        fi
        if sshd -t 2>/dev/null; then
            systemctl reload sshd 2>/dev/null || service sshd reload 2>/dev/null || true
            log "sshd reloaded"
        else
            fail "sshd config invalid after removal — check ${SSHD_CONFIG}"
        fi
    fi
fi

# ── Summary ──
echo ""
echo "========================================"
echo "Revocation complete."
echo ""
echo "Verification:"
echo "  ssh -T -i <key> ${OPS_USER}@<host>    # must fail"
echo "  sudo -u ${OPS_USER} docker version     # must fail"
echo ""
echo "NOT removed (manual if needed):"
echo "  JAR:   ${JAR_DST}"
echo "  Env:   ${ENV_FILE}"
echo "  User:  ${OPS_USER} (locked, not deleted)"
echo ""
echo "To restore docker group (ONLY if reverting to old prototype):"
echo "  usermod -aG docker ${OPS_USER}  # NOT recommended; docker group = root-level"
