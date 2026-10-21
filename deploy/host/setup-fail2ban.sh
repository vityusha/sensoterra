#!/usr/bin/env bash
# Install and configure fail2ban on the VPS. Run as root from the app directory:
#   sudo ./deploy/host/setup-fail2ban.sh
#   sudo IGNOREIP="203.0.113.7" ./deploy/host/setup-fail2ban.sh    # own addresses — never ban
# Safe to re-run: configs are overwritten from deploy/host/fail2ban/ and fail2ban is reloaded.
# Jails: sshd and sensoterra-auth (app logins through the host nginx).
set -euo pipefail

cd "$(dirname "$0")"

log() { echo "[fail2ban] $*"; }
die() { log "ERROR: $*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)"

SSH_PORT="${SSH_PORT:-$(sshd -T 2>/dev/null | awk '$1 == "port" { print $2; exit }')}"
[[ "$SSH_PORT" =~ ^[0-9]+$ ]] || die "cannot detect the SSH port, set SSH_PORT"
IGNOREIP="${IGNOREIP:-}"

if ! command -v fail2ban-client >/dev/null; then
    log "installing fail2ban"
    apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q fail2ban
fi

# Where sshd logs: /var/log/auth.log (rsyslog) or journald only (Debian 12 without rsyslog).
if [ -s /var/log/auth.log ]; then
    SSH_BACKEND=auto
else
    SSH_BACKEND=systemd
fi

[ -f /var/log/nginx/access.log ] || log "WARNING: /var/log/nginx/access.log not found — is host nginx installed? (sensoterra-auth jail needs it)"

if sshd -T 2>/dev/null | grep -qi '^passwordauthentication yes'; then
    log "WARNING: SSH password authentication is enabled — prefer keys only (PasswordAuthentication no)"
fi

sed -e "s/@SSH_PORT@/${SSH_PORT}/" -e "s/@SSH_BACKEND@/${SSH_BACKEND}/" -e "s#@IGNOREIP@#${IGNOREIP}#" \
    fail2ban/jail.d/sensoterra.local > /etc/fail2ban/jail.d/sensoterra.local
install -m 644 fail2ban/filter.d/sensoterra-auth.conf /etc/fail2ban/filter.d/sensoterra-auth.conf
chmod 644 /etc/fail2ban/jail.d/sensoterra.local

# Test the config before reloading: a broken config must not take down a running fail2ban.
fail2ban-client -t >/dev/null || die "fail2ban config test failed: fail2ban-client -t"

systemctl enable fail2ban >/dev/null 2>&1
if systemctl is-active -q fail2ban; then
    fail2ban-client reload
else
    systemctl start fail2ban
fi
sleep 2
fail2ban-client status
fail2ban-client status sshd
fail2ban-client status sensoterra-auth
log "OK (ssh port ${SSH_PORT}, ssh log backend ${SSH_BACKEND})"
