#!/usr/bin/env bash
# Ежедневный бэкап БД во внешнее хранилище (restic). Запускается systemd-таймером
# (deploy/systemd/sensoterra-backup.timer), можно и руками из каталога приложения:
#   ./deploy/backup.sh                 # дамп -> проверка -> restic backup -> forget/prune -> check
#   ./deploy/backup.sh init            # один раз: создать репозиторий restic
#   ./deploy/backup.sh restic <args>   # любая команда restic, например: snapshots, stats, unlock
#
# Настройки — в .env.backup рядом с .env (chmod 600), шаблон: deploy/env.backup.example.
# restic работает из контейнера, на хосте ставить ничего не нужно.
# В репозиторий кладутся дамп (pg_dump -Fc) и .env: без секретов из него восстановленная
# база бесполезна. Репозиторий шифруется RESTIC_PASSWORD — его храните ВНЕ VPS.
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "ERROR: .env not found in $(pwd)" >&2; exit 1; }
[ -f .env.backup ] || { echo "ERROR: .env.backup not found in $(pwd), see deploy/env.backup.example" >&2; exit 1; }

# shellcheck source=deploy/restic-lib.sh
. deploy/restic-lib.sh

KEEP_LOCAL="$(backup_value KEEP_LOCAL_DUMPS)"; KEEP_LOCAL="${KEEP_LOCAL:-3}"
KEEP_DAILY="$(backup_value RESTIC_KEEP_DAILY)"; KEEP_DAILY="${KEEP_DAILY:-7}"
KEEP_WEEKLY="$(backup_value RESTIC_KEEP_WEEKLY)"; KEEP_WEEKLY="${KEEP_WEEKLY:-4}"
KEEP_MONTHLY="$(backup_value RESTIC_KEEP_MONTHLY)"; KEEP_MONTHLY="${KEEP_MONTHLY:-12}"
HEALTHCHECK_URL="$(backup_value HEALTHCHECK_URL)"

log() { echo "[backup] $*"; }

case "${1:-}" in
    init)
        restic init
        exit 0
        ;;
    restic)
        shift
        restic "$@"
        exit 0
        ;;
    "") ;;
    *)
        echo "usage: $0 [init | restic <args>]" >&2
        exit 2
        ;;
esac

# Пинг мониторинга (healthchecks.io и совместимые): /start, успех, /fail.
# Без HEALTHCHECK_URL пропускается; сбой самого пинга бэкап не роняет.
ping_hc() {
    [ -n "$HEALTHCHECK_URL" ] || return 0
    curl -fsS -m 10 --retry 3 -o /dev/null "${HEALTHCHECK_URL}$1" || log "WARN: healthcheck ping failed"
}
trap '[ $? -eq 0 ] || ping_hc /fail' EXIT
ping_hc /start

# ---------- дамп ----------
mkdir -p backups/daily
DUMP="backups/daily/sensoterra-$(date +%Y%m%d-%H%M%S).dump"
log "pg_dump -> $DUMP"
# -Z0: без сжатия в pg_dump — restic сам сжимает и дедуплицирует, а сжатый дамп
# каждый день был бы новым набором байт и съедал бы место в хранилище целиком.
# shellcheck disable=SC2016 # переменные раскрываются внутри контейнера БД
compose exec -T timescaledb sh -c 'pg_dump -Fc -Z0 -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$DUMP.tmp"

# Дамп должен читаться: оглавление разбирается и в нём есть таблицы приложения.
TOC="$(compose exec -T timescaledb pg_restore --list < "$DUMP.tmp")"
if ! grep -q ' TABLE public app_user ' <<<"$TOC"; then
    rm -f "$DUMP.tmp"
    log "ERROR: dump has no app_user table — refusing to upload it"
    exit 1
fi
mv "$DUMP.tmp" "$DUMP"
log "dump OK: $(du -h "$DUMP" | cut -f1), $(grep -c ' TABLE DATA ' <<<"$TOC") tables with data"

# ---------- restic ----------
log "restic backup"
restic_with_dump "$DUMP" backup /backup --tag daily

log "restic forget --prune (daily $KEEP_DAILY, weekly $KEEP_WEEKLY, monthly $KEEP_MONTHLY)"
restic forget --prune --tag daily \
    --keep-daily "$KEEP_DAILY" --keep-weekly "$KEEP_WEEKLY" --keep-monthly "$KEEP_MONTHLY"

# Целостность структуры репозитория; сами данные раз в месяц читает restore.sh check.
log "restic check"
restic check

# Локально — несколько последних дампов для быстрого восстановления без скачивания.
# shellcheck disable=SC2012 # имена дампов формируем сами
ls -1t backups/daily/sensoterra-*.dump | tail -n +"$((KEEP_LOCAL + 1))" | xargs -r rm --

ping_hc ""
log "OK"
