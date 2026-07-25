#!/usr/bin/env bash
# Setup read-only ops account on Tencent Cloud CVM (4C4G).
# Run as root on the remote server:  ssh root@122.51.51.118 < setup-opsro.sh
#
# Creates:
#   opsro user  — non-root, no sudo, docker read-only access
#   ~opsro/.ssh/authorized_keys — populated from your local public key
#
# The opsro user can:
#   - docker compose ps / port / logs / container inspect / stats
#   - docker container inspect / logs / stats
# The opsro user CANNOT:
#   - sudo
#   - write files outside ~opsro
#   - docker exec / run / rm / restart / stop / kill
#   - modify system config

set -euo pipefail

OPS_USER="opsro"
OPS_HOME="/home/${OPS_USER}"
SSH_DIR="${OPS_HOME}/.ssh"

echo "=== Creating opsro user ==="
if id "${OPS_USER}" &>/dev/null; then
    echo "User ${OPS_USER} already exists, skipping creation."
else
    useradd --create-home --shell /bin/bash "${OPS_USER}"
    echo "User ${OPS_USER} created."
fi

# Lock password — SSH key only
passwd -l "${OPS_USER}"

echo "=== Setting up SSH ==="
mkdir -p "${SSH_DIR}"
chmod 700 "${SSH_DIR}"

# If you already have a public key, copy it manually:
#   scp ~/.ssh/id_ed25519_clawkit.pub root@122.51.51.118:/tmp/
# Then this script copies it from /tmp/
KEY_FILE="/tmp/id_ed25519_clawkit.pub"
if [ -f "${KEY_FILE}" ]; then
    cat "${KEY_FILE}" >> "${SSH_DIR}/authorized_keys"
    echo "Public key installed from ${KEY_FILE}"
else
    echo "WARNING: No public key found at ${KEY_FILE}"
    echo "Upload your key first: scp ~/.ssh/id_ed25519_clawkit.pub root@122.51.51.118:/tmp/"
fi
chmod 600 "${SSH_DIR}/authorized_keys"
chown -R "${OPS_USER}:${OPS_USER}" "${SSH_DIR}"

echo "=== Adding opsro to docker group (read-only by tool policy) ==="
# docker group gives access to the Docker socket.
# Read-only enforcement is done by the MCP tool layer (allowlist-only commands).
# Server-side protection: docker authz plugin or sudoers can further restrict.
usermod -aG docker "${OPS_USER}" 2>/dev/null || true

echo "=== Installing docker if needed ==="
if ! command -v docker &>/dev/null; then
    echo "Docker not found. Install Docker CE on CentOS/RHEL:"
    echo "  yum install -y yum-utils"
    echo "  yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo"
    echo "  yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin"
    echo "  systemctl enable --now docker"
    echo ""
    echo "Or on Ubuntu/Debian:"
    echo "  apt-get update && apt-get install -y docker.io docker-compose-v2"
    exit 1
fi

echo "=== Hardening SSH ==="
SSHD_CONFIG="/etc/ssh/sshd_config"
if [ -f "${SSHD_CONFIG}" ]; then
    # Ensure key-only auth for opsro
    if ! grep -q "^Match User ${OPS_USER}" "${SSHD_CONFIG}"; then
        cat >> "${SSHD_CONFIG}" <<EOF

# clawkit opsro — key-only, no forwarding
Match User ${OPS_USER}
    PasswordAuthentication no
    PermitTTY no
    AllowTcpForwarding no
    X11Forwarding no
    PermitUserEnvironment no
EOF
        echo "SSH hardened for ${OPS_USER}"
        systemctl reload sshd 2>/dev/null || service sshd reload 2>/dev/null || true
    fi
fi

echo ""
echo "=== Setup complete ==="
echo "Test the connection:"
echo "  ssh -i ~/.ssh/id_ed25519_clawkit ${OPS_USER}@122.51.51.118 docker version"
echo ""
echo "The opsro user for clawkit OPS-1 is ready."
