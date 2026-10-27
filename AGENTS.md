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
- Device access model (`DeviceAccessService`): account role is only `ADMIN`/`USER`; per-device `device_access.access_role` is `OWNER` (one per device, partial unique index from `V3__device_owner.sql`), `USER` (control) or `VIEWER` (read). Only the owner or an admin grants/revokes access and sends invites; the owner can't be removed or downgraded by guests. The first key claim (or the first `USER` grant on an ownerless device) makes the owner; admins transfer ownership via `accessRole: OWNER` on `/api/admin/users/{id}/devices`. Check control with `canControlRole()` (server and `client/src/store/auth.js`), never `=== 'USER'`.
- Every authenticated request passes `security/DbAccountSecurityRule`: the account must exist and be enabled (else 401), and `@Secured("ADMIN")` routes need ADMIN in the DB, not just in the JWT. HTTP Basic auth is disabled (`micronaut.security.basic-auth.enabled: false`); keep it off.
- `Device.deviceKey` is `@JsonIgnore`: entities are returned as JSON directly, so a secret field on an entity leaks to everyone who can read it. Admins read keys via `GET /api/devices/{chipId}/key`.
- MQTT input is untrusted: payloads over 16 KB are dropped, `Zone` outside UTC-12..+14 is ignored.
- MQTT client ID must be unique per running server (`SENSOTERRA_MQTT_CLIENT_ID`), otherwise the broker keeps kicking both. The server sets no MQTT Last Will and publishes no presence topic (server health is `/health`); never put a Last Will on a device topic — the broker would publish `offline` on behalf of the device. `MqttClientService` still clears the retained leftover of the removed `sensoterra/server/status` topic on connect.
- Timescale image is pinned (`2.30.2-pg16`); never downgrade it — the extension version in the existing volume must be loadable.
- In compose, `client/nginx.conf` serves the SPA and proxies `/api` + `/ws` to `server:8080`, forwarding `Host` so Micronaut's CORS check passes.

## Server tests

- Self-contained: H2 in PostgreSQL mode, Flyway disabled, `hbm2ddl=create` (see `server/src/test/resources/application.yml`). No Docker, MQTT broker, or network needed.
- **Tests never run Flyway migrations** — a broken migration passes `./gradlew test`. Verify migration SQL against the real docker DB when touching `db/migration/`.
- `GoogleOAuthTest` and `PasswordChangeTest` start `FakeGoogle` on fixed port **18443**, `YandexOAuthTest` starts `FakeYandex` on **18444** — ports must be free; don't run two test JVMs at once.

## Schema changes

- Production uses `hbm2ddl: none` — entity edits alone do nothing. The current fresh-install baseline is `server/src/main/resources/db/migration/V1__init.sql`; add a new `V<N>__*.sql` after the highest version for each later schema change, and never edit an already-applied migration. The former V1–V30 history is not compatible with this consolidated baseline.

## Gotchas

- Vite dev proxies `/api` and `/ws` → `:8080` (`client/vite.config.js`); the client opens its WebSocket at same-origin `/ws/status`.
- WebSocket auth: browser can't send headers on WS, so the JWT goes as `?token=` query param; validated in `ws/DeviceStatusWebSocket.java`.
- Micronaut's built-in `/login` is disabled; login only via `POST /api/auth/login`.
- Sliding session: JWT lives 24 h (`AuthController.TOKEN_TTL_SECONDS`) and carries `auth_time` (login time). While the user is active (API calls, visible tab) the client calls `POST /api/auth/refresh` once less than 12 h are left (`refreshTokenIfNeeded` in `client/src/api/auth.js`); the server refuses after 30 days from login (`MAX_SESSION_SECONDS`). An expired or rejected token (API 401 outside the login forms, WebSocket close 1008, refused refresh, expired `exp` in the router guard) ends the session: `sessionExpired()` → logout → `/login?expired=1` with "Сессия истекла, войдите снова". New token generators must set `auth_time` too.
- Google OAuth is off by default: requires `GOOGLE_OAUTH_ENABLED=true` + `GOOGLE_CLIENT_ID` (button hidden otherwise, exposed through `/api/auth/status`).
- Yandex ID is off by default: `YANDEX_OAUTH_ENABLED=true` + `YANDEX_CLIENT_ID` + `YANDEX_CLIENT_SECRET`. Code flow with redirect: the SPA keeps state/PKCE/context in sessionStorage (`utils/yandexOAuth.js`), Yandex returns to the `/oauth/yandex` route, the server exchanges the code (`YandexOAuthClient`). External accounts of both providers are created in `ExternalAccountService`, outside the provider round trip.
- Placeholder defaults containing `:` (URLs, JDBC) must be backtick-quoted: ``${VAR:`https://...`}`` — otherwise Micronaut treats the part before `:` as another placeholder and silently drops it (this broke Google JWKS with a 503). Same for `@Value` defaults.
- MQTT settings come from `.env` through compose: `SENSOTERRA_MQTT_BROKER`, `SENSOTERRA_MQTT_CLIENT_ID` (placeholders with defaults in `application.yml`), `SENSOTERRA_MQTT_USERNAME`/`PASSWORD` (required, like `JWT_SECRET`).
- The service worker is hand-rolled (`client/public/sw.js` + `registerSW.js` loaded from `index.html`).
- CORS (allowed origins incl. private-LAN regex) lives in `server/src/main/resources/application.yml` — keep `http://localhost:3000` allowed.
- Write code/config comments in English (older code still has Russian comments; don't mass-translate them unless asked).
