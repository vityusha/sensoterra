# Общие функции для backup.sh и restore.sh. Подключается через `.`, из корня приложения.
# shellcheck shell=bash

# Версия зафиксирована: формат репозитория должен читаться и при восстановлении.
RESTIC_IMAGE="restic/restic:0.17.3"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)

env_value() { grep -sE "^$1=" .env | tail -n1 | cut -d= -f2- || true; }
backup_value() { grep -sE "^$1=" .env.backup | tail -n1 | cut -d= -f2- || true; }

compose() { "${COMPOSE[@]}" "$@"; }

# restic в контейнере: настройки хранилища — из .env.backup, кэш — в backups/.restic-cache.
# Под uid/gid текущего пользователя, чтобы восстановленные файлы и кэш не стали root-овыми.
# Дополнительные аргументы docker run — через массив RESTIC_DOCKER_ARGS.
restic() {
    mkdir -p backups/.restic-cache
    docker run --rm -i \
        --user "$(id -u):$(id -g)" \
        --hostname sensoterra \
        --env-file .env.backup \
        -e RESTIC_CACHE_DIR=/cache \
        -v "$PWD/backups/.restic-cache:/cache" \
        "${RESTIC_DOCKER_ARGS[@]}" \
        "$RESTIC_IMAGE" "$@"
}

# Пути в снапшоте постоянные (/backup/sensoterra.dump, /backup/env) — иначе restic forget
# считал бы каждый день отдельной группой и не удалял старые снапшоты.
restic_with_dump() {
    local dump="$1"; shift
    RESTIC_DOCKER_ARGS=(
        -v "$PWD/$dump:/backup/sensoterra.dump:ro"
        -v "$PWD/.env:/backup/env:ro"
    )
    restic "$@"
    RESTIC_DOCKER_ARGS=()
}

RESTIC_DOCKER_ARGS=()
