#!/usr/bin/env bash
# setup-opsfix.sh — P0 remote restricted repair identity installation
#
# Creates the opsfix user with no docker group, no shell, no general sudo.
# Only allows restart_service(serviceId=order-api) via a fixed forced-command
# gateway, executed through a root-owned launcher script.
#
# Design: separate SSH key from opsro, separate forced-command gateway,
# separate launcher. The fix gateway only accepts restart_service(order-api).
#
# Usage (as root on remote host):
#   1. scp clawkit-ops-fix-gateway clawkit-ops-fix-stdio root@<host>:/tmp/
#   2. scp ~/.ssh/id_ed25519_clawkit_opsfix.pub root@<host>:/tmp/
#   3. scp extensions/clawkit-ops-mcp/target/clawkit-ops-mcp-*.jar root@<host>:/tmp/ops-fix.jar
#   4. ssh root@<host> < setup-opsfix.sh
#
# Idempotent: repeated runs must not duplicate or relax permissions.

set -euo pipefail

OPS_USER="opsfix"
OPS_HOME="/home/${OPS_USER}"
SSH_DIR="${OPS_HOME}/.ssh"
AUTH_KEYS="${SSH_DIR}/authorized_keys"
KEY_FILE="/tmp/id_ed25519_clawkit_opsfix.pub"
JAR_SRC="/tmp/ops-fix.jar"
JAR_DST="/usr/local/lib/clawkit/ops-fix.jar"
GATEWAY_SRC="/tmp/clawkit-ops-fix-gateway"
GATEWAY_DST="/usr/local/sbin/clawkit-ops-fix-gateway"
LAUNCHER_SRC="/tmp/clawkit-ops-fix-stdio"
LAUNCHER_DST="/usr/local/sbin/clawkit-ops-fix-stdio"
ENV_DIR="/etc/clawkit"
ENV_FILE="${ENV_DIR}/ops-fix.env"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsfix"
SSHD_CONFIG="/etc/ssh/sshd_config"
ERRORS=0

log()  { echo "  [OK] $*"; }
warn() { echo "  [WARN] $*" >&2; }
fail() { echo "  [FAIL] $*" >&2; ERRORS=$((ERRORS + 1)); }

# ── Preflight ──
echo "=== Preflight ==="

if [[ "$(id -u)" -ne 0 ]]; then
    echo "FATAL: this script must run as root" >&2
    exit 1
fi

if ! command -v java &>/dev/null; then
    echo "FATAL: Java 21+ not found" >&2
    exit 1
fi
echo "  Java: $(java -version 2>&1 | head -1 || echo 'unknown')"

if ! command -v sshd &>/dev/null && ! systemctl is-active sshd &>/dev/null 2>&1; then
    echo "FATAL: sshd not found or not running" >&2
    exit 1
fi
echo "  sshd: active"

if ! command -v docker &>/dev/null; then
    echo "FATAL: docker not found" >&2
    exit 1
fi
echo "  docker: $(docker --version 2>/dev/null || echo 'present')"

# ── Create / fix opsfix user ──
echo ""
echo "=== User: ${OPS_USER} ==="

if id "${OPS_USER}" &>/dev/null; then
    log "user ${OPS_USER} exists"
else
    useradd --create-home --shell /usr/sbin/nologin "${OPS_USER}"
    log "user ${OPS_USER} created (shell=nologin)"
fi

# Lock password — SSH key only
passwd -l "${OPS_USER}" >/dev/null 2>&1 || true

# ── REMOVE from docker group ──
echo ""
echo "=== Docker group removal ==="

if groups "${OPS_USER}" 2>/dev/null | grep -qw docker; then
    gpasswd -d "${OPS_USER}" docker 2>/dev/null || true
    deluser "${OPS_USER}" docker 2>/dev/null || true
    if groups "${OPS_USER}" 2>/dev/null | grep -qw docker; then
        fail "could not remove ${OPS_USER} from docker group"
    else
        log "removed ${OPS_USER} from docker group"
    fi
else
    log "${OPS_USER} is not in docker group"
fi

# ── Verify no docker socket access ──
if sudo -u "${OPS_USER}" docker version &>/dev/null 2>&1; then
    fail "${OPS_USER} can still access Docker socket — aborting"
    exit 1
else
    log "${OPS_USER} cannot access Docker socket"
fi

# ── Verify no general sudo ──
if sudo -u "${OPS_USER}" sudo -l &>/dev/null 2>&1; then
    warn "${OPS_USER} has some sudo access — will be restricted by sudoers below"
fi

# ── SSH directory and key ──
echo ""
echo "=== SSH authorized_keys ==="

mkdir -p "${SSH_DIR}"
chmod 700 "${SSH_DIR}"

if [[ ! -f "${KEY_FILE}" ]]; then
    fail "public key not found at ${KEY_FILE}"
    echo "  Upload your key first: scp ~/.ssh/id_ed25519_clawkit_opsfix.pub root@<host>:/tmp/"
    exit 1
fi

KEY_CONTENT=$(cat "${KEY_FILE}" | tr -s ' ' | head -1)

# restrict: disable PTY, forwarding, agent, X11, user rc
# command="...": force execution of the fixed fix gateway
FORCED_LINE="restrict,command=\"${GATEWAY_DST}\" ${KEY_CONTENT}"

if [[ -f "${AUTH_KEYS}" ]] && grep -qF "${KEY_CONTENT}" "${AUTH_KEYS}" 2>/dev/null; then
    if grep -qF "restrict,command=" "${AUTH_KEYS}" 2>/dev/null; then
        log "authorized_keys already has forced-command entry — unchanged"
    else
        local ts
        ts=$(date +%s)
        cp "${AUTH_KEYS}" "${AUTH_KEYS}.bak.${ts}"
        grep -vF "${KEY_CONTENT}" "${AUTH_KEYS}.bak.${ts}" > "${AUTH_KEYS}" || true
        echo "${FORCED_LINE}" >> "${AUTH_KEYS}"
        log "replaced plain key entry with forced-command entry"
    fi
else
    echo "${FORCED_LINE}" >> "${AUTH_KEYS}"
    log "installed forced-command authorized_keys entry"
fi

chmod 600 "${AUTH_KEYS}"
chown -R "${OPS_USER}:${OPS_USER}" "${SSH_DIR}"

# ── Install gateway and launcher ──
echo ""
echo "=== Gateway and launcher ==="

mkdir -p "$(dirname "${GATEWAY_DST}")"
mkdir -p "$(dirname "${LAUNCHER_DST}")"
mkdir -p "$(dirname "${JAR_DST}")"

for src_dst in "${GATEWAY_SRC}:${GATEWAY_DST}" "${LAUNCHER_SRC}:${LAUNCHER_DST}"; do
    SRC="${src_dst%%:*}"
    DST="${src_dst##*:}"
    if [[ -f "${SRC}" ]]; then
        cp "${SRC}" "${DST}"
        chown root:root "${DST}"
        chmod 0755 "${DST}"
        log "installed $(basename "${DST}")"
    else
        fail "$(basename "${SRC}") not found at ${SRC}"
    fi
done

# Install JAR
if [[ -f "${JAR_SRC}" ]]; then
    cp "${JAR_SRC}" "${JAR_DST}"
    chown root:root "${JAR_DST}"
    chmod 0644 "${JAR_DST}"
    log "installed ops-fix.jar"
else
    warn "JAR not found at ${JAR_SRC} — install manually"
fi

# Ensure opsfix cannot modify gateway, launcher, or JAR
for path in "${GATEWAY_DST}" "${LAUNCHER_DST}" "$(dirname "${JAR_DST}")"; do
    if [[ -e "${path}" ]]; then
        chown root:root "${path}" 2>/dev/null || true
        chmod go-w "${path}" 2>/dev/null || true
    fi
done

# ── Root-only environment file ──
echo ""
echo "=== Environment ==="

mkdir -p "${ENV_DIR}"
chmod 0755 "${ENV_DIR}"
chown root:root "${ENV_DIR}"

if [[ ! -f "${ENV_FILE}" ]]; then
    cat > "${ENV_FILE}" <<'ENVEOF'
# clawkit OPS FIX environment — root-only, NOT readable by opsfix
CLAWKIT_OPS_PROFILE=FIX_ORDER_API_V1
# Override for actual compose file path (copied from ops-mcp.env if needed)
# CLAWKIT_OPS_COMPOSE_FILE=/opt/clawkit/fixtures/clawkit-ops-r6/compose.yaml
# CLAWKIT_OPS_PROJECT=clawkit-ops-r6
ENVEOF
    chmod 0600 "${ENV_FILE}"
    chown root:root "${ENV_FILE}"
    log "created root-only env file at ${ENV_FILE}"
else
    chmod 0600 "${ENV_FILE}"
    chown root:root "${ENV_FILE}"
    log "env file already exists — permissions verified"
fi

# Verify opsfix cannot read env file
if sudo -u "${OPS_USER}" test -r "${ENV_FILE}" 2>/dev/null; then
    fail "${OPS_USER} can read env file ${ENV_FILE}"
else
    log "${OPS_USER} cannot read env file"
fi

# ── sudoers ──
echo ""
echo "=== sudoers ==="

SUDOERS_CONTENT="${OPS_USER} ALL=(root) NOPASSWD: ${LAUNCHER_DST}"
SUDOERS_DEFAULTS="Defaults:${OPS_USER} env_reset,!setenv,secure_path=/usr/sbin:/usr/bin:/sbin:/bin"

mkdir -p "$(dirname "${SUDOERS_FILE}")"

if [[ -f "${SUDOERS_FILE}" ]]; then
    if grep -qF "${LAUNCHER_DST}" "${SUDOERS_FILE}" 2>/dev/null; then
        log "sudoers entry already present — unchanged"
    else
        echo "${SUDOERS_CONTENT}" >> "${SUDOERS_FILE}"
        log "appended sudoers entry"
    fi
else
    printf '%s\n%s\n' "${SUDOERS_DEFAULTS}" "${SUDOERS_CONTENT}" > "${SUDOERS_FILE}"
    chmod 0440 "${SUDOERS_FILE}"
    log "created sudoers file"
fi

# Validate sudoers syntax
if visudo -c -f "${SUDOERS_FILE}" >/dev/null 2>&1; then
    log "sudoers syntax valid"
else
    fail "sudoers syntax invalid — check ${SUDOERS_FILE}"
fi

# ── sshd_config hardening ──
echo ""
echo "=== SSH hardening ==="

if [[ -f "${SSHD_CONFIG}" ]]; then
    SSHD_MATCH="Match User ${OPS_USER}"

    SSHD_BACKUP="${SSHD_CONFIG}.bak.$(date +%s)"
    cp "${SSHD_CONFIG}" "${SSHD_BACKUP}"
    log "backed up sshd_config to ${SSHD_BACKUP}"

    if ! grep -qF "${SSHD_MATCH}" "${SSHD_CONFIG}"; then
        cat >> "${SSHD_CONFIG}" <<SSHEOF

# clawkit opsfix — key-only, no PTY, no forwarding, no shell
Match User opsfix
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PermitTTY no
    DisableForwarding yes
    PermitUserRC no
SSHEOF
        log "appended sshd_config Match block for ${OPS_USER}"

        if sshd -t 2>/dev/null; then
            systemctl reload sshd 2>/dev/null || service sshd reload 2>/dev/null || true
            log "sshd config valid — reloaded"
        else
            cp "${SSHD_BACKUP}" "${SSHD_CONFIG}"
            sshd -t 2>/dev/null || warn "sshd config still invalid after rollback"
            fail "sshd config invalid — rolled back to backup"
        fi
    else
        log "sshd_config Match block already present"
        if ! sshd -t 2>/dev/null; then
            cp "${SSHD_BACKUP}" "${SSHD_CONFIG}"
            fail "sshd config invalid — rolled back to backup"
        else
            log "sshd config valid"
        fi
    fi
else
    warn "sshd_config not found at ${SSHD_CONFIG}"
fi

# ── Summary ──
echo ""
echo "========================================"
if [[ "${ERRORS}" -gt 0 ]]; then
    echo "Setup completed with ${ERRORS} error(s)."
    echo "Review [FAIL] lines above before proceeding."
    exit 1
else
    echo "Setup completed successfully."
fi

echo ""
echo "Next steps:"
echo "  1. Test connection:  ssh -T -i <opsfix-key> ${OPS_USER}@<host>"
echo "  2. Run verify script: ssh root@<host> < verify-opsfix.sh"
echo "  3. After E2E:         ssh root@<host> < revoke-opsfix.sh"
