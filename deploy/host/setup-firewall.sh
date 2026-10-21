#!/usr/bin/env bash
# Firewall VPS на ufw: входящие только SSH, 80, 443. Запускать от root на VPS:
#   sudo ./deploy/host/setup-firewall.sh            # показать, что будет сделано
#   sudo ./deploy/host/setup-firewall.sh --apply    # применить
# Порт SSH берётся из `sshd -T` (то есть фактический, включая Port в sshd_config.d);
# переопределить: SSH_PORT=2222 sudo -E ./deploy/host/setup-firewall.sh --apply
#
# Docker публикует порты в обход ufw (свои цепочки iptables). Поэтому в docker-compose.prod.yml
# клиент слушает только 127.0.0.1, а БД не публикуется — скрипт проверяет, что так и осталось.
set -euo pipefail

APPLY=""
[ "${1:-}" = "--apply" ] && APPLY=1

log() { echo "[firewall] $*"; }
die() { log "ERROR: $*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)"
command -v sshd >/dev/null || die "sshd not found"

SSH_PORT="${SSH_PORT:-$(sshd -T 2>/dev/null | awk '$1 == "port" { print $2; exit }')}"
[[ "$SSH_PORT" =~ ^[0-9]+$ ]] || die "cannot detect the SSH port, set SSH_PORT"

if ! command -v ufw >/dev/null; then
    if [ -n "$APPLY" ]; then
        log "installing ufw"
        apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q ufw
    else
        log "ufw is not installed (will be installed with --apply)"
    fi
fi

# Порты, опубликованные Docker наружу (не на loopback) — ufw их не закрывает.
if command -v docker >/dev/null; then
    exposed="$(docker ps --format '{{.Names}} {{.Ports}}' \
        | grep -E '(0\.0\.0\.0|\[::\]|:::)[0-9]+->' || true)"
    if [ -n "$exposed" ]; then
        log "WARNING: these containers publish ports on all interfaces, ufw does NOT filter them:"
        while read -r line; do log "  $line"; done <<<"$exposed"
    fi
fi

log "rules: deny incoming by default; allow ${SSH_PORT}/tcp (SSH, rate-limited), 80/tcp, 443/tcp"
if [ -z "$APPLY" ]; then
    log "dry run — nothing changed; re-run with --apply"
    exit 0
fi

# Порядок важен: SSH разрешаем до включения, иначе текущая сессия оборвётся.
# limit: не больше 6 новых соединений за 30 с с одного IP (деплою из GitHub Actions нужно 2).
ufw default deny incoming
ufw default allow outgoing
ufw limit "${SSH_PORT}/tcp" comment 'SSH'
ufw allow 80/tcp comment 'HTTP (redirect to HTTPS, certbot)'
ufw allow 443/tcp comment 'HTTPS'
# Старый 22/tcp, если SSH переехал на другой порт и правило осталось от провайдера.
if [ "$SSH_PORT" != 22 ]; then
    ufw delete allow 22/tcp >/dev/null 2>&1 || true
    ufw delete allow OpenSSH >/dev/null 2>&1 || true
fi
ufw --force enable
ufw status verbose
log "OK. Check from ANOTHER terminal that a new SSH session to port ${SSH_PORT} still works before closing this one."
