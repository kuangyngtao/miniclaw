#!/usr/bin/env bash
# verify-opsro.sh — P0 remote security interface verification
#
# Design doc §6.4, PR-M1 §3.1: mechanical checks of artifact ownership,
# permissions, sudoers, and SSH configuration. Run as root.
# Does NOT modify anything. All checks run, then summary.
#
# Exit code 0 = all checks pass. Non-zero = at least one failure.

set -uo pipefail  # NOT -e — we accumulate failures

OPS_USER="opsro"
GATEWAY="/usr/local/sbin/clawkit-ops-gateway"
LAUNCHER="/usr/local/sbin/clawkit-ops-mcp-stdio"
JAR="/usr/local/lib/clawkit/ops-mcp.jar"
ENV_FILE="/etc/clawkit/ops-mcp.env"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsro"
AUTH_KEYS="/home/${OPS_USER}/.ssh/authorized_keys"
SSHD_CONFIG="/etc/ssh/sshd_config"
ERRORS=0

# check: positive assertion — $@ is a command that must succeed
check() {
    local desc="$1"; shift
    if "$@" &>/dev/null; then
        echo "  [PASS] ${desc}"
    else
        echo "  [FAIL] ${desc}"
        ERRORS=$((ERRORS + 1))
    fi
}

# check_not: negative assertion — $@ is a command that must FAIL
check_not() {
    local desc="$1"; shift
    if "$@" &>/dev/null; then
        echo "  [FAIL] ${desc}"
        ERRORS=$((ERRORS + 1))
    else
        echo "  [PASS] ${desc}"
    fi
}

echo "=== clawkit-ops-mcp P0 security verification ==="
echo ""

# ── User identity (§6.1) ──
echo "--- User ---"
check "opsro user exists" id "${OPS_USER}"
check "opsro password is locked" sh -c "passwd -S '${OPS_USER}' 2>/dev/null | grep -q ' L '"
check_not "opsro NOT in docker group" sh -c "groups '${OPS_USER}' 2>/dev/null | grep -qw docker"
check_not "opsro cannot access Docker socket" sudo -u "${OPS_USER}" docker version
check_not "opsro has only fixed launcher sudo (no general sudo)" sh -c "sudo -u '${OPS_USER}' sudo -l 2>/dev/null | grep -v '${LAUNCHER}' | grep -q 'ALL'"

echo ""
echo "--- Gateway and launcher (§6.2) ---"
check "gateway exists" test -f "${GATEWAY}"
check "gateway root-owned" test "$(stat -c '%U' "${GATEWAY}" 2>/dev/null || echo NOTROOT)" = "root"
check "gateway not group-writable" sh -c "test \"\$(stat -c '%a' '${GATEWAY}' 2>/dev/null | cut -c2)\" -le 5"
check "gateway not other-writable" sh -c "test \"\$(stat -c '%a' '${GATEWAY}' 2>/dev/null | cut -c3)\" -le 5"
check_not "gateway not opsro-writable" sudo -u "${OPS_USER}" test -w "${GATEWAY}"

check "launcher exists" test -f "${LAUNCHER}"
check "launcher root-owned" test "$(stat -c '%U' "${LAUNCHER}" 2>/dev/null || echo NOTROOT)" = "root"
check "launcher not group-writable" sh -c "test \"\$(stat -c '%a' '${LAUNCHER}' 2>/dev/null | cut -c2)\" -le 5"
check "launcher not other-writable" sh -c "test \"\$(stat -c '%a' '${LAUNCHER}' 2>/dev/null | cut -c3)\" -le 5"
check_not "launcher not opsro-writable" sudo -u "${OPS_USER}" test -w "${LAUNCHER}"

echo ""
echo "--- JAR (§6.2) ---"
if [[ -f "${JAR}" ]]; then
    check "JAR exists" true
    check "JAR root-owned" test "$(stat -c '%U' "${JAR}" 2>/dev/null || echo NOTROOT)" = "root"
    check_not "JAR not opsro-writable" sudo -u "${OPS_USER}" test -w "${JAR}"
else
    check "JAR installed" false
fi

echo ""
echo "--- Environment file (§6.2) ---"
check "env file exists" test -f "${ENV_FILE}"
check "env file root-owned" test "$(stat -c '%U' "${ENV_FILE}" 2>/dev/null || echo NOTROOT)" = "root"
check "env file mode 0600" test "$(stat -c '%a' "${ENV_FILE}" 2>/dev/null || echo NOT600)" = "600"
check_not "env file not opsro-readable" sudo -u "${OPS_USER}" test -r "${ENV_FILE}"

echo ""
echo "--- sudoers (§6.2) ---"
check "sudoers file exists" test -f "${SUDOERS_FILE}"
check "sudoers file mode 0440" test "$(stat -c '%a' "${SUDOERS_FILE}" 2>/dev/null || echo NOT440)" = "440"
check "sudoers syntax valid" visudo -c -f "${SUDOERS_FILE}"
check "sudoers contains fixed launcher path" grep -qF "${LAUNCHER}" "${SUDOERS_FILE}"
check "sudoers has env_reset" grep -q 'env_reset' "${SUDOERS_FILE}"
check "sudoers has !setenv" grep -q '!setenv' "${SUDOERS_FILE}"
check "sudoers only allows fixed launcher (no wildcard)" sh -c "! grep -q '[*]' '${SUDOERS_FILE}'"

echo ""
echo "--- SSH authorized_keys (§6.1) ---"
if [[ -f "${AUTH_KEYS}" ]]; then
    check "authorized_keys exists" true
    check "authorized_keys mode 0600" test "$(stat -c '%a' "${AUTH_KEYS}" 2>/dev/null || echo NOT600)" = "600"
    check "authorized_keys has restrict" grep -q 'restrict' "${AUTH_KEYS}"
    check "authorized_keys has forced-command" grep -q 'command=' "${AUTH_KEYS}"
    check "authorized_keys references gateway" grep -qF "${GATEWAY}" "${AUTH_KEYS}"
else
    check "authorized_keys exists" false
fi

echo ""
echo "--- sshd_config effective policy (§6.1, PR-M1 §3.1) ---"
# Use sshd -T -C to verify the effective configuration for opsro
# rather than grepping the config file directly.
if command -v sshd &>/dev/null; then
    check "sshd config syntax valid" sshd -t
    check "sshd effective: PasswordAuthentication=no for opsro" sh -c \
        "sshd -T -C user=${OPS_USER},host=localhost,addr=127.0.0.1 2>/dev/null | grep -q '^passwordauthentication no$'"
    check "sshd effective: PermitTTY=no for opsro" sh -c \
        "sshd -T -C user=${OPS_USER},host=localhost,addr=127.0.0.1 2>/dev/null | grep -q '^permittty no$'"
    check "sshd effective: disableforwarding=yes for opsro" sh -c \
        "sshd -T -C user=${OPS_USER},host=localhost,addr=127.0.0.1 2>/dev/null | grep -q '^disableforwarding yes$'"
else
    check "sshd available" false
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
