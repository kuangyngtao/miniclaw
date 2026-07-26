#!/usr/bin/env bash
# setup-opsro.sh — P0 remote read-only security interface installation
#
# Design doc §6.1–§6.4. Run as root on the remote server.
# Idempotent: repeated runs must not duplicate keys, sudoers entries,
# or relax file permissions. Failures leave the server in a recognizable
# state with a non-zero exit code.
#
# Usage:
#   1. scp clawkit-ops-gateway clawkit-ops-mcp-stdio root@<host>:/tmp/
#   2. scp ~/.ssh/id_ed25519_clawkit.pub root@<host>:/tmp/
#   3. scp extensions/clawkit-ops-mcp/target/clawkit-ops-mcp-*.jar root@<host>:/tmp/ops-mcp.jar
#   4. ssh root@<host> < setup-opsro.sh
#
# Second admin channel (root/cloud console) MUST remain available
# throughout. Do NOT run this as the sole access mechanism.

set -euo pipefail

OPS_USER="opsro"
OPS_HOME="/home/${OPS_USER}"
SSH_DIR="${OPS_HOME}/.ssh"
AUTH_KEYS="${SSH_DIR}/authorized_keys"
KEY_FILE="/tmp/id_ed25519_clawkit.pub"
JAR_SRC="/tmp/ops-mcp.jar"
JAR_DST="/usr/local/lib/clawkit/ops-mcp.jar"
GATEWAY_SRC="/tmp/clawkit-ops-gateway"
GATEWAY_DST="/usr/local/sbin/clawkit-ops-gateway"
LAUNCHER_SRC="/tmp/clawkit-ops-mcp-stdio"
LAUNCHER_DST="/usr/local/sbin/clawkit-ops-mcp-stdio"
ENV_DIR="/etc/clawkit"
ENV_FILE="${ENV_DIR}/ops-mcp.env"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsro"
SSHD_CONFIG="/etc/ssh/sshd_config"
ERRORS=0

log()  { echo "  [OK] $*"; }
warn() { echo "  [WARN] $*" >&2; }
fail() { echo "  [FAIL] $*" >&2; ERRORS=$((ERRORS + 1)); }

# ── Preflight checks ──
echo "=== Preflight ==="

if [[ "$(id -u)" -ne 0 ]]; then
    echo "FATAL: this script must run as root" >&2
    exit 1
fi

if ! command -v java &>/dev/null; then
    echo "FATAL: Java 21+ not found" >&2
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 || echo "unknown")
echo "  Java: ${JAVA_VERSION}"

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

# ── Create / fix opsro user (§6.1) ──
echo ""
echo "=== User: ${OPS_USER} ==="

if id "${OPS_USER}" &>/dev/null; then
    log "user ${OPS_USER} exists"
else
    useradd --create-home --shell /bin/bash "${OPS_USER}"
    log "user ${OPS_USER} created"
fi

# Lock password — SSH key only
passwd -l "${OPS_USER}" >/dev/null 2>&1 || true

# ── REMOVE from docker group (§6.1) ──
echo ""
echo "=== Docker group removal ==="

if groups "${OPS_USER}" 2>/dev/null | grep -qw docker; then
    gpasswd -d "${OPS_USER}" docker 2>/dev/null || true
    # On some systems gpasswd may not be available; try deluser on Debian
    deluser "${OPS_USER}" docker 2>/dev/null || true
    if groups "${OPS_USER}" 2>/dev/null | grep -qw docker; then
        fail "could not remove ${OPS_USER} from docker group"
    else
        log "removed ${OPS_USER} from docker group"
    fi
else
    log "${OPS_USER} is not in docker group"
fi

# ── Verify no docker socket access (§6.1) ──
if sudo -u "${OPS_USER}" docker version &>/dev/null 2>&1; then
    fail "${OPS_USER} can still access Docker socket — aborting"
    exit 1
else
    log "${OPS_USER} cannot access Docker socket"
fi

# ── SSH directory and key (§6.1) ──
echo ""
echo "=== SSH authorized_keys ==="

mkdir -p "${SSH_DIR}"
chmod 700 "${SSH_DIR}"

if [[ ! -f "${KEY_FILE}" ]]; then
    fail "public key not found at ${KEY_FILE}"
    echo "  Upload your key first: scp ~/.ssh/id_ed25519_clawkit.pub root@<host>:/tmp/"
    exit 1
fi

KEY_CONTENT=$(cat "${KEY_FILE}" | tr -s ' ' | head -1)

# Build the forced-command line (§6.1)
# restrict: disable PTY, forwarding, agent, X11, user rc
# command="...": force execution of the fixed gateway
FORCED_LINE="restrict,command=\"${GATEWAY_DST}\" ${KEY_CONTENT}"

if [[ -f "${AUTH_KEYS}" ]] && grep -qF "${KEY_CONTENT}" "${AUTH_KEYS}" 2>/dev/null; then
    # Key already present — check if forced-command is set
    if grep -qF "restrict,command=" "${AUTH_KEYS}" 2>/dev/null; then
        log "authorized_keys already has forced-command entry — unchanged"
    else
        # Old entry without forced-command — replace it
        local ts
        ts=$(date +%s)
        cp "${AUTH_KEYS}" "${AUTH_KEYS}.bak.${ts}"
        grep -vF "${KEY_CONTENT}" "${AUTH_KEYS}.bak.${ts}" > "${AUTH_KEYS}" || true
        echo "${FORCED_LINE}" >> "${AUTH_KEYS}"
        log "replaced plain key entry with forced-command entry"
    fi
else
    # New key
    echo "${FORCED_LINE}" >> "${AUTH_KEYS}"
    log "installed forced-command authorized_keys entry"
fi

chmod 600 "${AUTH_KEYS}"
chown -R "${OPS_USER}:${OPS_USER}" "${SSH_DIR}"

# ── Install gateway and launcher (§6.2) ──
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
    log "installed ops-mcp.jar"
else
    warn "JAR not found at ${JAR_SRC} — install manually"
fi

# Ensure opsro cannot modify gateway, launcher, or JAR
for path in "${GATEWAY_DST}" "${LAUNCHER_DST}" "$(dirname "${JAR_DST}")"; do
    if [[ -e "${path}" ]]; then
        chown root:root "${path}" 2>/dev/null || true
        # Remove any non-root write permissions
        chmod go-w "${path}" 2>/dev/null || true
    fi
done

# ── Root-only environment file (§6.2) ──
echo ""
echo "=== Environment ==="

mkdir -p "${ENV_DIR}"
chmod 0755 "${ENV_DIR}"
chown root:root "${ENV_DIR}"

if [[ ! -f "${ENV_FILE}" ]]; then
    cat > "${ENV_FILE}" <<'ENVEOF'
# clawkit OPS MCP environment — root-only, NOT readable by opsro
# Populate with your Docker allowlist, DB credentials, etc.
CLAWKIT_OPS_PROFILE=APP_DOWN_V1
# CLAWKIT_OPS_PROFILE=POSTGRES_DIAGNOSIS_V1
ENVEOF
    chmod 0600 "${ENV_FILE}"
    chown root:root "${ENV_FILE}"
    log "created root-only env file at ${ENV_FILE}"
else
    # Ensure permissions haven't drifted
    chmod 0600 "${ENV_FILE}"
    chown root:root "${ENV_FILE}"
    log "env file already exists — permissions verified"
fi

# Verify opsro cannot read env file
if sudo -u "${OPS_USER}" test -r "${ENV_FILE}" 2>/dev/null; then
    fail "${OPS_USER} can read env file ${ENV_FILE}"
else
    log "${OPS_USER} cannot read env file"
fi

# ── sudoers (§6.2) ──
echo ""
echo "=== sudoers ==="

SUDOERS_CONTENT="${OPS_USER} ALL=(root) NOPASSWD: ${LAUNCHER_DST}"
SUDOERS_DEFAULTS="Defaults:${OPS_USER} env_reset,!setenv,secure_path=/usr/sbin:/usr/bin:/sbin:/bin"

mkdir -p "$(dirname "${SUDOERS_FILE}")"

if [[ -f "${SUDOERS_FILE}" ]]; then
    if grep -qF "${LAUNCHER_DST}" "${SUDOERS_FILE}" 2>/dev/null; then
        log "sudoers entry already present — unchanged"
    else
        # Append new entry without duplicating defaults
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

# ── sshd_config hardening (§6.1, PR-M1 §3.2) ──
echo ""
echo "=== SSH hardening ==="

if [[ -f "${SSHD_CONFIG}" ]]; then
    SSHD_MATCH="Match User ${OPS_USER}"

    # Backup before any modification
    SSHD_BACKUP="${SSHD_CONFIG}.bak.$(date +%s)"
    cp "${SSHD_CONFIG}" "${SSHD_BACKUP}"
    log "backed up sshd_config to ${SSHD_BACKUP}"

    if ! grep -qF "${SSHD_MATCH}" "${SSHD_CONFIG}"; then
        # ── PermitUserEnvironment must be global (§3.2) ──
        # Appending it first, before the Match block.
        # The Match block does NOT include PermitUserEnvironment
        # (it is not allowed in Match blocks).
        if ! grep -q '^PermitUserEnvironment' "${SSHD_CONFIG}"; then
            sed -i '1a PermitUserEnvironment no' "${SSHD_CONFIG}"
            log "added global PermitUserEnvironment no"
        fi

        cat >> "${SSHD_CONFIG}" <<'SSHEOF'

# clawkit opsro — key-only, no PTY, no forwarding (§6.1)
Match User opsro
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PermitTTY no
    DisableForwarding yes
    PermitUserRC no
SSHEOF
        log "appended sshd_config Match block for ${OPS_USER}"

        # Validate before reloading; rollback on failure (§3.2)
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
        # Still validate config
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
echo "  1. Test connection:  ssh -T -i <key> ${OPS_USER}@<host>"
echo "  2. Run verify script: ssh root@<host> < verify-opsro.sh"
echo "  3. After E2E:         ssh root@<host> < revoke-opsro.sh"
