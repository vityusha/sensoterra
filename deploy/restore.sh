#!/usr/bin/env bash
# Restore the database from a backup. Run from the app directory on the VPS:
#   ./deploy/restore.sh check [SOURCE]          # restore into a temporary container and verify
#   ./deploy/restore.sh apply SOURCE [--yes]    # restore INTO THE PRODUCTION DB (asks for confirmation)
#   ./deploy/restore.sh env [SOURCE]            # extract .env from a snapshot to backups/restore/env
#
# SOURCE: latest (default for check), a restic snapshot ID (./deploy/backup.sh restic snapshots)
# or a path to a local dump (backups/daily/*.dump, backups/pre-deploy-*.dump).
#
# check also runs monthly from a systemd timer (sensoterra-restore-check.timer): it is the
# only way to know the backups actually restore.
# apply dumps the current database to backups/pre-restore-*.dump before restoring.
set -euo pipefail

cd "$(dirname "$0")/.."
MODE="${1:-}"
# env is also needed on a new VPS that has no .env yet — .env.backup is enough for it.
[ -f .env ] || [ "$MODE" = env ] || { echo "ERROR: .env not found in $(pwd)" >&2; exit 1; }

# shellcheck source=deploy/restic-lib.sh
. deploy/restic-lib.sh

SOURCE="${2:-}"
ASSUME_YES=""
[ "${3:-}" = "--yes" ] && ASSUME_YES=1

DB_NAME="$(env_value POSTGRES_DB)"
DB_USER="$(env_value POSTGRES_USER)"
# Same image as in compose: the timescaledb extension version must match the dump.
DB_IMAGE="$(grep -m1 -oE 'timescale/timescaledb:[^[:space:]"]+' docker-compose.yml || true)"
HEALTHCHECK_URL=""
[ -f .env.backup ] && HEALTHCHECK_URL="$(backup_value HEALTHCHECK_RESTORE_URL)"

SAFETY_DUMP=""
log() { echo "[restore] $*"; }
die() {
    log "ERROR: $*"
    if [ -n "$SAFETY_DUMP" ]; then
        log "the server is stopped; the database state before the restore is in $SAFETY_DUMP:"
        log "  ./deploy/restore.sh apply $SAFETY_DUMP"
    fi
    exit 1
}

usage() {
    sed -n '2,5p' "$0" >&2
    exit 2
}

WORK=""
CHECK_CT=""
cleanup() {
    [ -n "$CHECK_CT" ] && docker rm -fv "$CHECK_CT" >/dev/null 2>&1
    [ -n "$WORK" ] && rm -rf "$WORK"
    return 0
}
trap cleanup EXIT

# Fetches a file from a restic snapshot into a temporary backups/restore/<time>/ (removed
# on exit); the path goes to FETCHED. No $(...): WORK must stay in this process for cleanup.
FETCHED=""
fetch_from_restic() {
    local snapshot="$1" file="$2"
    [ -f .env.backup ] || die ".env.backup not found — restic repository is not configured"
    WORK="backups/restore/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$WORK"
    log "fetching /backup/$file from restic snapshot $snapshot"
    RESTIC_DOCKER_ARGS=(-v "$PWD/$WORK:/restore")
    restic restore "$snapshot" --target /restore --include "/backup/$file"
    RESTIC_DOCKER_ARGS=()
    [ -s "$WORK/backup/$file" ] || die "snapshot $snapshot has no /backup/$file"
    FETCHED="$WORK/backup/$file"
}

# The dump path goes to DUMP.
DUMP=""
resolve_dump() {
    local source="${1:-latest}"
    if [ -f "$source" ]; then
        DUMP="$source"
    else
        fetch_from_restic "$source" sensoterra.dump
        DUMP="$FETCHED"
    fi
}

psql_in() {
    local ct="$1" db="$2"; shift 2
    docker exec -i "$ct" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$db" "$@"
}

# TimescaleDB procedure: pre_restore stops background jobs and the extension's triggers,
# otherwise chunks and continuous aggregates restore with errors.
restore_into() {
    local ct="$1" dump="$2"
    psql_in "$ct" "$DB_NAME" -c 'CREATE EXTENSION IF NOT EXISTS timescaledb' \
        -c 'SELECT timescaledb_pre_restore()' >/dev/null
    log "pg_restore $(du -h "$dump" | cut -f1) into $ct"
    if ! docker exec -i "$ct" pg_restore -U "$DB_USER" -d "$DB_NAME" --exit-on-error < "$dump"; then
        psql_in "$ct" "$DB_NAME" -c 'SELECT timescaledb_post_restore()' >/dev/null || true
        die "pg_restore failed"
    fi
    psql_in "$ct" "$DB_NAME" -c 'SELECT timescaledb_post_restore()' >/dev/null
}

# Checks on the restored database. Fails if it is clearly incomplete.
verify() {
    local ct="$1" out
    out="$(psql_in "$ct" "$DB_NAME" -At -F ' ' <<'SQL'
SELECT 'migrations', count(*) FROM flyway_schema_history WHERE success;
SELECT 'failed_migrations', count(*) FROM flyway_schema_history WHERE NOT success;
SELECT 'hypertables', count(*) FROM timescaledb_information.hypertables;
SELECT 'continuous_aggregates', count(*) FROM timescaledb_information.continuous_aggregates;
SELECT 'users', count(*) FROM app_user;
SELECT 'devices', count(*) FROM device;
SELECT 'sensors', count(*) FROM sensor;
SELECT 'sensor_telemetry_rows', count(*) FROM sensor_telemetry;
SELECT 'last_telemetry', coalesce(to_char(max(time) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'), 'none') FROM sensor_telemetry;
SQL
)"
    while read -r line; do log "  $line"; done <<<"$out"
    val() { awk -v k="$1" '$1 == k { print $2 }' <<<"$out"; }
    [ "$(val migrations)" -ge 1 ] || die "no applied Flyway migrations"
    [ "$(val failed_migrations)" -eq 0 ] || die "failed Flyway migrations in the dump"
    [ "$(val hypertables)" -ge 1 ] || die "no hypertables — TimescaleDB metadata is missing"
    [ "$(val users)" -ge 1 ] || die "app_user is empty"
    log "verification OK"
}

wait_ready() {
    local ct="$1" deadline=$((SECONDS + 120))
    # Over TCP: during initdb the server listens on the unix socket only, so pg_isready there lies.
    until docker exec "$ct" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME"; do
        [ "$SECONDS" -lt "$deadline" ] || die "temporary database did not start"
        sleep 2
    done
}

cmd_check() {
    ping_restore /start
    resolve_dump "${SOURCE:-latest}"
    local dump="$DUMP"
    CHECK_CT="sensoterra-restore-check-$$"
    log "starting temporary $DB_IMAGE ($CHECK_CT)"
    # Limits, so the check does not starve the production DB on the same VPS.
    docker run -d --name "$CHECK_CT" \
        --memory 768m --shm-size 256m \
        -e POSTGRES_DB="$DB_NAME" \
        -e POSTGRES_USER="$DB_USER" \
        -e POSTGRES_PASSWORD="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" \
        -e TS_TUNE_MEMORY=512MB -e TS_TUNE_NUM_CPUS=1 \
        "$DB_IMAGE" >/dev/null
    wait_ready "$CHECK_CT"
    restore_into "$CHECK_CT" "$dump"
    verify "$CHECK_CT"
    ping_restore ""
    log "OK: ${SOURCE:-latest} restores cleanly"
}

cmd_apply() {
    [ -n "$SOURCE" ] || usage
    local dump ct answer safety
    resolve_dump "$SOURCE"
    dump="$DUMP"
    ct="$(compose ps -q timescaledb)"
    [ -n "$ct" ] || die "timescaledb container is not running (docker compose ... up -d timescaledb)"

    if [ -z "$ASSUME_YES" ]; then
        log "This REPLACES database '$DB_NAME' with $dump. The server will be stopped."
        read -r -p "[restore] Type 'restore' to continue: " answer
        [ "$answer" = "restore" ] || die "aborted"
    fi

    mkdir -p backups
    safety="backups/pre-restore-$(date +%Y%m%d-%H%M%S).dump"
    log "dumping current database -> $safety"
    # shellcheck disable=SC2016 # variables expand inside the DB container
    compose exec -T timescaledb sh -c 'pg_dump -Fc -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$safety"

    log "stopping server"
    compose stop server
    SAFETY_DUMP="$safety"
    psql_in "$ct" postgres \
        -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)" \
        -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\""
    restore_into "$ct" "$dump"
    verify "$ct"
    SAFETY_DUMP=""

    log "starting server"
    compose up -d --no-build server client
    log "OK: database restored from $SOURCE (previous state: $safety)"
}

cmd_env() {
    if [ -n "$SOURCE" ] && [ -f "$SOURCE" ]; then
        die "env is taken from a restic snapshot, not from a local file"
    fi
    fetch_from_restic "${SOURCE:-latest}" env
    install -m 600 "$FETCHED" backups/restore/env
    log "OK: backups/restore/env (compare with .env before using it)"
}

ping_restore() {
    [ "$MODE" = check ] && [ -n "$HEALTHCHECK_URL" ] || return 0
    curl -fsS -m 10 --retry 3 -o /dev/null "${HEALTHCHECK_URL}$1" || log "WARN: healthcheck ping failed"
}

if [ "$MODE" != env ]; then
    { [ -n "$DB_NAME" ] && [ -n "$DB_USER" ]; } || die "POSTGRES_DB / POSTGRES_USER are not set in .env"
    [ -n "$DB_IMAGE" ] || die "cannot find the timescaledb image in docker-compose.yml"
fi

case "$MODE" in
    check)
        trap '[ $? -eq 0 ] || ping_restore /fail; cleanup' EXIT
        cmd_check
        ;;
    apply) cmd_apply ;;
    env) cmd_env ;;
    *) usage ;;
esac
