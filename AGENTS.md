# AGENTS.md

## Layout

Two independent apps, no monorepo tooling — each has its own build:

- `server/` — Java 21, Micronaut 4, Gradle wrapper (8.14.5). Entry: `ru.sensoterra.server.Application`.
- `client/` — Vue 3 + Vite 6, plain JS (no TypeScript, no ESLint/Prettier config).

## Commands

```bash
docker compose up -d --build    # full stack: DB + server + client(nginx) on :3000 — from repo root
docker compose up -d timescaledb   # DB only, for host-side dev
./deploy/deploy.sh <sha>        # on the VPS: backup, pull GHCR images, up, healthcheck, rollback on failure
./deploy/backup.sh              # on the VPS: daily pg_dump -> restic (systemd timer, settings in .env.backup)
./deploy/restore.sh check|apply|env [snapshot|latest|file.dump]   # restore test / restore into prod / extract .env
sudo ./deploy/host/setup-firewall.sh [--apply]   # on the VPS: ufw (SSH, 80, 443); dry run without --apply
sudo ./deploy/host/setup-fail2ban.sh             # on the VPS: fail2ban jails sshd + sensoterra-auth
cd server && ./gradlew build    # build + all tests
cd server && ./gradlew run      # serves :8080
cd client && npm install && npm run dev   # :3000
cd client && npm run build      # static output in client/dist/
```

- Always use `./gradlew` (plain `gradle` may not match wrapper 8.14.5). `gradle.properties` caps the build JVM at 256m / 2 workers — don't raise it casually.
- Single test: `./gradlew test --tests 'GoogleOAuthTest'`.
- Host-side dev start order matters: `docker compose up -d timescaledb` → `./gradlew run` (Flyway runs at startup; server fails without DB) → `npm run dev`.
- No lint / typecheck / frontend-test scripts exist — don't invent them.
- Root `.env` (gitignored) supplies `POSTGRES_*`, `JWT_SECRET`, MQTT and OAuth vars to compose. In compose the server's `DB_URL/DB_USER/DB_PASSWORD` are derived from `POSTGRES_*`; the `.env` `DB_*` values only matter for `./gradlew run` on the host.
- `V2__retention.sql` runs non-transactionally (`.sql.conf`) and holds compression/retention policies plus continuous aggregates `sensor_telemetry_hourly`/`_daily` built on `sensor_telemetry` columns — dropping/altering those columns requires recreating the aggregates.
- CD: push to `main` → `.github/workflows/deploy.yml` (reuses `ci.yml` via `workflow_call`; `ci.yml` skips direct pushes to main) → images to GHCR tagged with the SHA → SSH to the VPS → `deploy/deploy.sh`. The prod overlay has `build: !reset null` — the VPS never builds, it only pulls `${IMAGE_PREFIX}-server|client:${IMAGE_TAG}`. Validate workflow edits with actionlint and the script with shellcheck.
- Docs: `README.md` holds only general info (local dev, build, tests, config). Everything about production — VPS setup, deploy, backups, operations — goes into `docs/INSTALL-prod.md`.
- Prod profile `application-prod.yml` (`MICRONAUT_ENVIRONMENTS=prod`) disables CORS; dev CORS list stays in `application.yml`.
- Log levels: set only via `SENSOTERRA_LOG_LEVEL` in `logback.xml` (default DEBUG, `INFO` in `docker-compose.prod.yml`); don't add `logger.levels` to `application*.yml`.
- Health: `micronaut-management` is on the classpath only for `GET /health` (DB, disk, MQTT via `health/MqttHealthIndicator`); `endpoints.all.enabled: false` keeps every other management endpoint off. MQTT lost = `DEGRADED` with HTTP 200 (must not fail docker healthcheck / deploy). Client nginx proxies `/health`; docker healthcheck and `deploy.sh` use it.
- MQTT client ID must be unique per running server (`SENSOTERRA_MQTT_CLIENT_ID`), otherwise the broker keeps kicking both. The server's LWT goes to its own `status-topic` (`sensoterra/server/status`), never a device topic.
- Timescale image is pinned (`2.30.2-pg16`); never downgrade it — the extension version in the existing volume must be loadable.
- In compose, `client/nginx.conf` serves the SPA and proxies `/api` + `/ws` to `server:8080`, forwarding `Host` so Micronaut's CORS check passes.

## Server tests

- Self-contained: H2 in PostgreSQL mode, Flyway disabled, `hbm2ddl=create` (see `server/src/test/resources/application.yml`). No Docker, MQTT broker, or network needed.
- **Tests never run Flyway migrations** — a broken migration passes `./gradlew test`. Verify migration SQL against the real docker DB when touching `db/migration/`.
- `GoogleOAuthTest` and `PasswordChangeTest` start `FakeGoogle`, an HTTPS server on fixed port **18443** — port must be free; don't run two test JVMs at once.

## Schema changes

- Production uses `hbm2ddl: none` — entity edits alone do nothing. The current fresh-install baseline is `server/src/main/resources/db/migration/V1__init.sql`; add a new `V<N>__*.sql` after the highest version for each later schema change, and never edit an already-applied migration. The former V1–V30 history is not compatible with this consolidated baseline.

## Gotchas

- Vite dev proxies `/api` and `/ws` → `:8080` (`client/vite.config.js`); the client opens its WebSocket at same-origin `/ws/status`.
- WebSocket auth: browser can't send headers on WS, so the JWT goes as `?token=` query param; validated in `ws/DeviceStatusWebSocket.java`.
- Micronaut's built-in `/login` is disabled; login only via `POST /api/auth/login`.
- Google OAuth is off by default: requires `GOOGLE_OAUTH_ENABLED=true` + `GOOGLE_CLIENT_ID` (button hidden otherwise, exposed through `/api/auth/status`).
- Placeholder defaults containing `:` (URLs, JDBC) must be backtick-quoted: ``${VAR:`https://...`}`` — otherwise Micronaut treats the part before `:` as another placeholder and silently drops it (this broke Google JWKS with a 503). Same for `@Value` defaults.
- MQTT broker endpoint and device/topic defaults live in `application.yml`; MQTT username/password and JWT signing secret must be provided through `SENSOTERRA_MQTT_USERNAME`, `SENSOTERRA_MQTT_PASSWORD`, and `JWT_SECRET` environment variables.
- The service worker is hand-rolled (`client/public/sw.js` + `registerSW.js` loaded from `index.html`).
- CORS (allowed origins incl. private-LAN regex) lives in `server/src/main/resources/application.yml` — keep `http://localhost:3000` allowed.
- Write code/config comments in English (older code still has Russian comments; don't mass-translate them unless asked).
