#!/usr/bin/env bash
# Деплой на VPS. Вызывается из GitHub Actions по SSH (.github/workflows/deploy.yml),
# можно запускать и руками из каталога приложения:
#   ./deploy/deploy.sh <image-tag>                         # образы публичные или уже есть
#   echo "$TOKEN" | ./deploy/deploy.sh <image-tag> --login <github-user>   # токен GHCR из stdin
#
# Порядок: бэкап БД (Flyway мигрирует при старте) -> pull -> up -> ожидание healthcheck.
# Если новая версия не поднялась — контейнеры откатываются на предыдущий тег,
# БД автоматически НЕ восстанавливается (путь к дампу печатается).
set -euo pipefail
# Database dumps contain password hashes, device keys and invite tokens: owner-only files.
umask 077

TAG="${1:?usage: deploy.sh <image-tag> [--login <user>]}"
# The tag ends up in docker image names and in `sed` on .env: allow only a commit SHA or latest.
if ! [[ "$TAG" =~ ^[0-9a-f]{7,40}$ || "$TAG" == latest ]]; then
    echo "ERROR: invalid image tag '$TAG' (expected a commit SHA or 'latest')" >&2
    exit 2
fi
LOGIN_USER=""
if [ "${2:-}" = "--login" ]; then
    LOGIN_USER="${3:?--login requires a user}"
fi

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "ERROR: .env not found in $(pwd)" >&2; exit 1; }

env_value() { grep -E "^$1=" .env | tail -n1 | cut -d= -f2- || true; }

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
KEEP_BACKUPS="${KEEP_BACKUPS:-10}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
APP_PORT="$(env_value APP_PORT)"; APP_PORT="${APP_PORT:-3000}"
IMAGE_PREFIX="$(env_value IMAGE_PREFIX)"; IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/vityusha/sensoterra}"
PREV_TAG="$(env_value IMAGE_TAG)"

log() { echo "[deploy] $*"; }

if [ -n "$LOGIN_USER" ]; then
    read -r GHCR_TOKEN
    echo "$GHCR_TOKEN" | docker login ghcr.io -u "$LOGIN_USER" --password-stdin >/dev/null
    trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
fi

# ---------- бэкап ----------
BACKUP=""
mkdir -p backups
if [ -n "$(IMAGE_TAG="${PREV_TAG:-latest}" "${COMPOSE[@]}" ps --status running -q timescaledb)" ]; then
    BACKUP="backups/pre-deploy-$(date +%Y%m%d-%H%M%S)-${PREV_TAG:-none}.dump"
    log "backup -> $BACKUP"
    # shellcheck disable=SC2016 # переменные раскрываются внутри контейнера БД
    IMAGE_TAG="${PREV_TAG:-latest}" "${COMPOSE[@]}" exec -T timescaledb \
        sh -c 'pg_dump -Fc -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$BACKUP"
    # shellcheck disable=SC2012 # имена дампов формируем сами
    ls -1t backups/pre-deploy-*.dump | tail -n +"$((KEEP_BACKUPS + 1))" | xargs -r rm --
else
    log "database is not running yet — first deploy, no backup"
fi

# ---------- запуск ----------
up() {
    IMAGE_TAG="$1" "${COMPOSE[@]}" pull
    IMAGE_TAG="$1" "${COMPOSE[@]}" up -d --no-build --remove-orphans
}

healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT)) id status
    while [ "$SECONDS" -lt "$deadline" ]; do
        id="$(IMAGE_TAG="$1" "${COMPOSE[@]}" ps -q server)"
        status="$( [ -n "$id" ] && docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo none)"
        if [ "$status" = "healthy" ] && curl -fsS -o /dev/null "http://127.0.0.1:${APP_PORT}/health"; then
            return 0
        fi
        sleep 3
    done
    return 1
}

log "deploying $TAG (previous: ${PREV_TAG:-none})"
# up сам падает, если server не стал healthy (client ждёт его через depends_on) —
# это тоже повод для отката, поэтому он внутри условия, а не под set -e.
if ! up "$TAG" || ! healthy "$TAG"; then
    log "ERROR: $TAG did not become healthy in ${HEALTH_TIMEOUT}s. Server log tail:"
    IMAGE_TAG="$TAG" "${COMPOSE[@]}" logs --tail 80 server || true
    if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$TAG" ]; then
        log "rolling containers back to $PREV_TAG"
        up "$PREV_TAG" || true
    fi
    [ -n "$BACKUP" ] && log "database was NOT restored; pre-deploy dump: $BACKUP"
    exit 1
fi

# IMAGE_TAG в .env — ручной `docker compose` на сервере поднимет ту же версию.
if grep -q '^IMAGE_TAG=' .env; then
    sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$TAG/" .env
else
    echo "IMAGE_TAG=$TAG" >> .env
fi

# Старые образы приложения: оставляем текущий и предыдущий (для отката).
docker image ls --format '{{.Repository}}:{{.Tag}}' \
    | grep -E "^${IMAGE_PREFIX}-(server|client):" \
    | grep -vE ":(${TAG}|${PREV_TAG:-__none__}|latest)$" \
    | xargs -r docker rmi >/dev/null 2>&1 || true
docker image prune -f >/dev/null

log "OK: $TAG is live"
