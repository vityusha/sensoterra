# Shared functions for backup.sh and restore.sh. Sourced with `.` from the app root.
# shellcheck shell=bash

# Pinned version: the repository format must stay readable at restore time.
RESTIC_IMAGE="restic/restic:0.17.3"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)

env_value() { grep -sE "^$1=" .env | tail -n1 | cut -d= -f2- || true; }
backup_value() { grep -sE "^$1=" .env.backup | tail -n1 | cut -d= -f2- || true; }

compose() { "${COMPOSE[@]}" "$@"; }

# restic in a container: storage settings from .env.backup, cache in backups/.restic-cache.
# Runs as the current uid/gid so restored files and the cache are not owned by root.
# Extra docker run arguments go through the RESTIC_DOCKER_ARGS array.
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

# Snapshot paths are fixed (/backup/sensoterra.dump, /backup/env) — otherwise restic forget
# would treat every day as a separate group and never remove old snapshots.
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
