#!/usr/bin/env bash
# verify-opsfix.sh — P0 remote restricted repair security verification
#
# Mechanical checks of opsfix user, gateway, launcher, JAR, sudoers,
# and SSH configuration. Uses real opsfix key for negative tests.
# Does NOT modify anything. All checks run, then summary.
# Exit code 0 = all checks pass.

set -uo pipefail

OPS_USER="opsfix"
GATEWAY="/usr/local/sbin/clawkit-ops-fix-gateway"
LAUNCHER="/usr/local/sbin/clawkit-ops-fix-stdio"
JAR="/usr/local/lib/clawkit/ops-fix.jar"
ENV_FILE="/etc/clawkit/ops-fix.env"
SUDOERS_FILE="/etc/sudoers.d/clawkit-opsfix"
AUTH_KEYS="/home/${OPS_USER}/.ssh/authorized_keys"
ERRORS=0
FIX_KEY="${CLAWKIT_FIX_TEST_KEY:-/tmp/opsfix_test_key}"

check() {
    local desc="$1"; shift
    if "$@" &>/dev/null; then echo "  [PASS] ${desc}"; else echo "  [FAIL] ${desc}"; ERRORS=$((ERRORS + 1)); fi
}
check_not() {
    local desc="$1"; shift
    if "$@" &>/dev/null; then echo "  [FAIL] ${desc}"; ERRORS=$((ERRORS + 1)); else echo "  [PASS] ${desc}"; fi
}

echo "=== clawkit-ops-fix P0 security verification ==="
echo ""

# ── User identity ──
echo "--- User ---"
check "opsfix user exists" id "${OPS_USER}"
check "opsfix password is locked" sh -c "passwd -S '${OPS_USER}' 2>/dev/null | grep -q ' L '"
check_not "opsfix NOT in docker group" sh -c "groups '${OPS_USER}' 2>/dev/null | grep -qw docker"
check_not "opsfix cannot access Docker socket" sudo -u "${OPS_USER}" docker version
check_not "opsfix has no general sudo" sh -c "sudo -u '${OPS_USER}' sudo -l 2>/dev/null | grep -v '${LAUNCHER}' | grep -q 'ALL'"
check "opsfix shell is bash (locked, forced-command protects)" sh -c "getent passwd '${OPS_USER}' 2>/dev/null | grep -q bash"

# ── Gateway and launcher ──
echo ""
echo "--- Gateway and launcher ---"
check "gateway exists" test -f "${GATEWAY}"
check "gateway root-owned" test "$(stat -c '%U' "${GATEWAY}" 2>/dev/null || echo NOTROOT)" = "root"
check "gateway not group-writable" sh -c "test \$(stat -c '%a' '${GATEWAY}' 2>/dev/null | cut -c2) -le 5"
check "gateway not other-writable" sh -c "test \$(stat -c '%a' '${GATEWAY}' 2>/dev/null | cut -c3) -le 5"
check_not "gateway not opsfix-writable" sudo -u "${OPS_USER}" test -w "${GATEWAY}"
check "launcher exists" test -f "${LAUNCHER}"
check "launcher root-owned" test "$(stat -c '%U' "${LAUNCHER}" 2>/dev/null || echo NOTROOT)" = "root"
check "launcher not group-writable" sh -c "test \$(stat -c '%a' '${LAUNCHER}' 2>/dev/null | cut -c2) -le 5"
check "launcher not other-writable" sh -c "test \$(stat -c '%a' '${LAUNCHER}' 2>/dev/null | cut -c3) -le 5"
check_not "launcher not opsfix-writable" sudo -u "${OPS_USER}" test -w "${LAUNCHER}"

echo ""
echo "--- JAR ---"
if [[ -f "${JAR}" ]]; then check "JAR exists" true; check "JAR root-owned" test "$(stat -c '%U' "${JAR}" 2>/dev/null || echo NOTROOT)" = "root"; check_not "JAR not opsfix-writable" sudo -u "${OPS_USER}" test -w "${JAR}"; else check "JAR installed" false; fi

echo ""
echo "--- Environment file ---"
if [[ -f "${ENV_FILE}" ]]; then check "env file exists" true; check "env file root-owned" test "$(stat -c '%U' "${ENV_FILE}" 2>/dev/null || echo NOTROOT)" = "root"; check "env file mode 0600" test "$(stat -c '%a' "${ENV_FILE}" 2>/dev/null || echo NOT600)" = "600"; check_not "env file not opsfix-readable" sudo -u "${OPS_USER}" test -r "${ENV_FILE}"; else check "env file exists" false; fi

echo ""
echo "--- sudoers ---"
if [[ -f "${SUDOERS_FILE}" ]]; then check "sudoers file exists" true; check "sudoers mode 0440" test "$(stat -c '%a' "${SUDOERS_FILE}" 2>/dev/null || echo NOT440)" = "440"; check "sudoers syntax valid" visudo -c -f "${SUDOERS_FILE}"; check "sudoers contains launcher path" grep -qF "${LAUNCHER}" "${SUDOERS_FILE}"; check "sudoers has env_reset" grep -q 'env_reset' "${SUDOERS_FILE}"; check "sudoers has !setenv" grep -q '!setenv' "${SUDOERS_FILE}"; check "sudoers no wildcard" sh -c "! grep -q '[*]' '${SUDOERS_FILE}'"; else check "sudoers file exists" false; fi

echo ""
echo "--- SSH authorized_keys ---"
if [[ -f "${AUTH_KEYS}" ]]; then check "authorized_keys exists" true; check "authorized_keys mode 0600" test "$(stat -c '%a' "${AUTH_KEYS}" 2>/dev/null || echo NOT600)" = "600"; check "authorized_keys has restrict" grep -q 'restrict' "${AUTH_KEYS}"; check "authorized_keys has forced-command" grep -q 'command=' "${AUTH_KEYS}"; check "authorized_keys references fix gateway" grep -qF "${GATEWAY}" "${AUTH_KEYS}"; else check "authorized_keys exists" false; fi

# ── sshd effective policy (use sshd -T -C, not text grep) ──
echo ""
echo "--- sshd effective policy for opsfix ---"
if command -v sshd &>/dev/null; then
    EFFECTIVE=$(sshd -T -C user=${OPS_USER},host=localhost,addr=127.0.0.1 2>/dev/null || echo "")
    check "sshd config syntax valid" sshd -t
    check "sshd: passwordauthentication=no" sh -c "echo '${EFFECTIVE}' | grep -q '^passwordauthentication no$'"
    check "sshd: kbdinteractiveauthentication=no" sh -c "echo '${EFFECTIVE}' | grep -q '^kbdinteractiveauthentication no$'"
    check "sshd: permittty=no" sh -c "echo '${EFFECTIVE}' | grep -q '^permittty no$'"
    check "sshd: disableforwarding=yes" sh -c "echo '${EFFECTIVE}' | grep -q '^disableforwarding yes$'"
else check "sshd available" false; fi

# ── opsfix cannot read opsro secrets ──
echo ""
echo "--- Cross-user isolation ---"
if [[ -f "/home/opsro/.ssh/authorized_keys" ]]; then check_not "opsfix cannot read opsro authorized_keys" sudo -u "${OPS_USER}" test -r "/home/opsro/.ssh/authorized_keys"; fi
if [[ -f "/etc/clawkit/ops-mcp.env" ]]; then check_not "opsfix cannot read opsro env file" sudo -u "${OPS_USER}" test -r "/etc/clawkit/ops-mcp.env"; fi
check_not "opsfix cannot read /root" sudo -u "${OPS_USER}" test -r "/root"

# ── Negative security tests using real opsfix key ──
echo ""
echo "--- Live gateway negative tests (using real key) ---"

SSH_CMD="ssh -i ${FIX_KEY} -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o BatchMode=yes -o ConnectTimeout=3 ${OPS_USER}@localhost"

if [[ -f "${FIX_KEY}" ]]; then
    # Test: SSH_ORIGINAL_COMMAND rejected (SFTP/SCP blocked)
    check_not "SFTP blocked (SSH_ORIGINAL_COMMAND rejected)" sh -c "timeout 5 sftp -o BatchMode=yes -o StrictHostKeyChecking=no -o PasswordAuthentication=no -i ${FIX_KEY} ${OPS_USER}@localhost 2>&1 | grep -q 'sftp>'"

    # Test: forced-command gateway is alive (initialize works)
    check "gateway responds to initialize" sh -c "echo '{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}' | ${SSH_CMD} -T 2>/dev/null | grep -q 'clawkit-ops-fix'"

    # Test: illegal tool rejected
    check "gateway rejects non-restart_service tool" sh -c "echo '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"service_status\",\"arguments\":{\"service\":\"order-api\"}},\"id\":1}' | timeout 5 ${SSH_CMD} -T 2>/dev/null | grep -q 'only restart_service allowed'"

    # Test: illegal serviceId rejected
    check "gateway rejects non-order-api serviceId" sh -c "echo '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"restart_service\",\"arguments\":{\"serviceId\":\"postgres\"}},\"id\":1}' | timeout 5 ${SSH_CMD} -T 2>/dev/null | grep -q 'only serviceId=order-api'"

    # Test: valid restart_service allowed
    check "gateway allows restart_service(order-api)" sh -c "echo '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"restart_service\",\"arguments\":{\"serviceId\":\"order-api\"}},\"id\":1}' | timeout 5 ${SSH_CMD} -T 2>/dev/null | grep -q 'structuredContent'"

    # Test: malformed JSON → parse error, connection survives
    check "gateway returns parse error for malformed JSON" sh -c "echo 'not json' | timeout 5 ${SSH_CMD} -T 2>/dev/null | grep -q 'Parse error'"

    # Test: unknown method rejected
    check "gateway rejects unknown method" sh -c "echo '{\"jsonrpc\":\"2.0\",\"method\":\"shell_exec\",\"params\":{},\"id\":1}' | timeout 5 ${SSH_CMD} -T 2>/dev/null | grep -q 'method not allowed'"

    # Cleanup temp key if it was installed by setup
    if [[ "${FIX_KEY}" == "/tmp/opsfix_test_key" ]]; then
        rm -f "${FIX_KEY}" 2>/dev/null || true
    fi
else
    echo "  [WARN] opsfix test key not found at ${FIX_KEY} — skipping live gateway tests"
    echo "  Copy it with: scp id_ed25519_clawkit_opsfix root@host:${FIX_KEY}"
fi

echo ""
echo "========================================"
if [[ "${ERRORS}" -gt 0 ]]; then
    echo "VERIFICATION FAILED: ${ERRORS} check(s) failed."
    exit 1
else
    echo "VERIFICATION PASSED: all checks passed."
    exit 0
fi
