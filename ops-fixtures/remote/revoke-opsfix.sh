#!/usr/bin/env bash
# revoke-opsfix.sh — P0 revoke opsfix access
#
# Removes the opsfix user, sudoers, authorized_keys, gateway, launcher,
# and sshd_config Match block. Idempotent. Run as root.
#
# Does NOT remove the JAR or env file (harmless without the sudoers chain).

set -euo pipefail

OPS_USER="opsfix"
GATEWAY="/usr/local/sbin/clawkit-ops-fix-gateway"
LAUNCHER="/usr/local/sbin/clawkit-ops-fix-stdio"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsfix"
AUTH_KEYS="/home/${OPS_USER}/.ssh/authorized_keys"
SSHD_CONFIG="/etc/ssh/sshd_config"
ERRORS=0

log()  { echo "  [OK] $*"; }
warn() { echo "  [WARN] $*" >&2; }

if [[ "$(id -u)" -ne 0 ]]; then
    echo "FATAL: this script must run as root" >&2
    exit 1
fi

echo "=== Revoking opsfix access ==="

# 1. Remove user
if id "${OPS_USER}" &>/dev/null; then
    pkill -u "${OPS_USER}" 2>/dev/null || true
    userdel -r "${OPS_USER}" 2>/dev/null || true
    log "removed user ${OPS_USER}"
else
    log "user ${OPS_USER} does not exist"
fi

# 2. Remove sudoers
if [[ -f "${SUDOERS_FILE}" ]]; then
    rm -f "${SUDOERS_FILE}"
    log "removed sudoers file"
else
    log "sudoers file already removed"
fi

# 3. Remove gateway and launcher
for path in "${GATEWAY}" "${LAUNCHER}"; do
    if [[ -f "${path}" ]]; then
        rm -f "${path}"
        log "removed $(basename "${path}")"
    else
        log "$(basename "${path}") already removed"
    fi
done

# 4. Remove sshd_config Match block
if [[ -f "${SSHD_CONFIG}" ]]; then
    SSHD_BACKUP="${SSHD_CONFIG}.bak.$(date +%s)"
    cp "${SSHD_CONFIG}" "${SSHD_BACKUP}"
    log "backed up sshd_config to ${SSHD_BACKUP}"

    if grep -qF "Match User ${OPS_USER}" "${SSHD_CONFIG}"; then
        # Remove the Match block for opsfix
        sed -i '/^# clawkit opsfix/,/^Match User opsfix/{d}' "${SSHD_CONFIG}" 2>/dev/null || true
        sed -i '/^Match User opsfix/,/^$/{
            /^Match User opsfix/d
            /^    PasswordAuthentication no/d
            /^    KbdInteractiveAuthentication no/d
            /^    PermitTTY no/d
            /^    DisableForwarding yes/d
            /^    PermitUserRC no/d
            /^$/d
        }' "${SSHD_CONFIG}" 2>/dev/null || true

        if sshd -t 2>/dev/null; then
            systemctl reload sshd 2>/dev/null || service sshd reload 2>/dev/null || true
            log "removed sshd_config Match block — reloaded"
        else
            cp "${SSHD_BACKUP}" "${SSHD_CONFIG}"
            sshd -t 2>/dev/null || warn "sshd config still invalid after rollback"
            warn "sshd config rollback applied — Match block preserved"
        fi
    else
        log "no Match block for ${OPS_USER} in sshd_config"
    fi
else
    warn "sshd_config not found"
fi

echo ""
echo "=== Revoke complete ==="
echo "Note: JAR at /usr/local/lib/clawkit/ops-fix.jar and env file"
echo "at /etc/clawkit/ops-fix.env were NOT removed (harmless without"
echo "the sudoers chain). Remove manually if desired."
