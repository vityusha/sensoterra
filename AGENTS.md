# AGENTS.md

## Layout

Two independent apps, no monorepo tooling — each has its own build:

- `server/` — Java 21, Micronaut 4, Gradle wrapper (8.14.5). Entry: `ru.sensoterra.server.Application`.
- `client/` — Vue 3 + Vite 6, plain JS (no TypeScript, no ESLint/Prettier config).

## Commands

```bash
docker compose up -d --build    # full stack: DB + server + client(nginx) on :3000 — from repo root
docker compose up -d timescaledb   # DB only, for host-side dev
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build   # prod overlay (VPS)
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
- Prod profile `application-prod.yml` (`MICRONAUT_ENVIRONMENTS=prod`) disables CORS and DEBUG logging; dev CORS list stays in `application.yml`.
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
- MQTT broker endpoint and device/topic defaults live in `application.yml`; MQTT username/password and JWT signing secret must be provided through `SENSOTERRA_MQTT_USERNAME`, `SENSOTERRA_MQTT_PASSWORD`, and `JWT_SECRET` environment variables.
- The service worker is hand-rolled (`client/public/sw.js` + `registerSW.js` loaded from `index.html`).
- CORS (allowed origins incl. private-LAN regex) lives in `server/src/main/resources/application.yml` — keep `http://localhost:3000` allowed.
- Code/config comments are in Russian — match the existing style.
