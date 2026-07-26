#!/usr/bin/env bash
# verify-opsro.sh — P0 remote security interface verification
#
# Design doc §6.4: mechanical checks of artifact ownership, permissions,
# sudoers, and SSH configuration. Run as root. Does NOT modify anything.
#
# Exit code 0 = all checks pass. Non-zero = at least one failure.

set -euo pipefail

OPS_USER="opsro"
GATEWAY="/usr/local/sbin/clawkit-ops-gateway"
LAUNCHER="/usr/local/sbin/clawkit-ops-mcp-stdio"
JAR="/usr/local/lib/clawkit/ops-mcp.jar"
ENV_FILE="/etc/clawkit/ops-mcp.env"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsro"
AUTH_KEYS="/home/${OPS_USER}/.ssh/authorized_keys"
SSHD_CONFIG="/etc/ssh/sshd_config"
ERRORS=0

check() {
    local desc="$1"; shift
    if "$@"; then
        echo "  [PASS] ${desc}"
    else
        echo "  [FAIL] ${desc}"
        ERRORS=$((ERRORS + 1))
    fi
}

echo "=== clawkit-ops-mcp P0 security verification ==="
echo ""

# ── User identity (§6.1) ──
echo "--- User ---"
check "opsro user exists" id "${OPS_USER}" &>/dev/null
check "opsro password is locked" passwd -S "${OPS_USER}" 2>/dev/null | grep -q "L"
check "opsro NOT in docker group" ! groups "${OPS_USER}" 2>/dev/null | grep -qw docker
check "opsro cannot access Docker socket" ! sudo -u "${OPS_USER}" docker version &>/dev/null 2>&1
check "opsro has no sudo privileges" ! sudo -u "${OPS_USER}" sudo -l &>/dev/null 2>&1

echo ""
echo "--- Gateway and launcher (§6.2) ---"
check "gateway exists" test -f "${GATEWAY}"
check "gateway root-owned" test "$(stat -c '%U' "${GATEWAY}" 2>/dev/null)" = "root"
check "gateway not group-writable" test "$(stat -c '%a' "${GATEWAY}" 2>/dev/null | cut -c2)" -le 5
check "gateway not other-writable" test "$(stat -c '%a' "${GATEWAY}" 2>/dev/null | cut -c3)" -le 5
check "gateway not opsro-writable" ! sudo -u "${OPS_USER}" test -w "${GATEWAY}" 2>/dev/null

check "launcher exists" test -f "${LAUNCHER}"
check "launcher root-owned" test "$(stat -c '%U' "${LAUNCHER}" 2>/dev/null)" = "root"
check "launcher not group-writable" test "$(stat -c '%a' "${LAUNCHER}" 2>/dev/null | cut -c2)" -le 5
check "launcher not other-writable" test "$(stat -c '%a' "${LAUNCHER}" 2>/dev/null | cut -c3)" -le 5
check "launcher not opsro-writable" ! sudo -u "${OPS_USER}" test -w "${LAUNCHER}" 2>/dev/null

echo ""
echo "--- JAR (§6.2) ---"
if [[ -f "${JAR}" ]]; then
    check "JAR exists" true
    check "JAR root-owned" test "$(stat -c '%U' "${JAR}" 2>/dev/null)" = "root"
    check "JAR not opsro-writable" ! sudo -u "${OPS_USER}" test -w "${JAR}" 2>/dev/null
else
    check "JAR installed (skipped — no JAR)" false
fi

echo ""
echo "--- Environment file (§6.2) ---"
check "env file exists" test -f "${ENV_FILE}"
check "env file root-owned" test "$(stat -c '%U' "${ENV_FILE}" 2>/dev/null)" = "root"
check "env file mode 0600" test "$(stat -c '%a' "${ENV_FILE}" 2>/dev/null)" = "600"
check "env file not opsro-readable" ! sudo -u "${OPS_USER}" test -r "${ENV_FILE}" 2>/dev/null

echo ""
echo "--- sudoers (§6.2) ---"
check "sudoers file exists" test -f "${SUDOERS_FILE}"
check "sudoers file mode 0440" test "$(stat -c '%a' "${SUDOERS_FILE}" 2>/dev/null)" = "440"
check "sudoers syntax valid" visudo -c -f "${SUDOERS_FILE}" &>/dev/null
check "sudoers contains fixed launcher path" grep -qF "${LAUNCHER}" "${SUDOERS_FILE}" 2>/dev/null
check "sudoers has env_reset" grep -q "env_reset" "${SUDOERS_FILE}" 2>/dev/null
check "sudoers has !setenv" grep -q "!setenv" "${SUDOERS_FILE}" 2>/dev/null

echo ""
echo "--- SSH authorized_keys (§6.1) ---"
if [[ -f "${AUTH_KEYS}" ]]; then
    check "authorized_keys exists" true
    check "authorized_keys mode 0600" test "$(stat -c '%a' "${AUTH_KEYS}" 2>/dev/null)" = "600"
    check "authorized_keys has restrict" grep -q "restrict" "${AUTH_KEYS}" 2>/dev/null
    check "authorized_keys has forced-command" grep -q "command=" "${AUTH_KEYS}" 2>/dev/null
    check "authorized_keys references gateway" grep -qF "${GATEWAY}" "${AUTH_KEYS}" 2>/dev/null
else
    check "authorized_keys exists" false
fi

echo ""
echo "--- sshd_config (§6.1) ---"
if [[ -f "${SSHD_CONFIG}" ]]; then
    check "sshd_config has Match User opsro" grep -qF "Match User ${OPS_USER}" "${SSHD_CONFIG}" 2>/dev/null
    check "Match block: PasswordAuthentication no" awk "/Match User ${OPS_USER}/,/^Match /" "${SSHD_CONFIG}" | grep -q "PasswordAuthentication no"
    check "Match block: PermitTTY no" awk "/Match User ${OPS_USER}/,/^Match /" "${SSHD_CONFIG}" | grep -q "PermitTTY no"
    check "Match block: DisableForwarding yes" awk "/Match User ${OPS_USER}/,/^Match /" "${SSHD_CONFIG}" | grep -q "DisableForwarding yes"
    check "sshd config syntax valid" sshd -t &>/dev/null
else
    check "sshd_config exists" false
fi

# ── Summary ──
echo ""
echo "========================================"
if [[ "${ERRORS}" -gt 0 ]]; then
    echo "VERIFICATION FAILED: ${ERRORS} check(s) failed."
    echo "Review [FAIL] lines above and re-run setup-opsro.sh."
    exit 1
else
    echo "VERIFICATION PASSED: all checks passed."
    exit 0
fi
