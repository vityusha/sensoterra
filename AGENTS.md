# AGENTS.md

## Layout

Two independent apps, no monorepo tooling — each has its own build:

- `server/` — Java 21, Micronaut 4, Gradle wrapper (8.14.5). Entry: `ru.sensoterra.server.Application`.
- `client/` — Vue 3 + Vite 6, plain JS (no TypeScript, no ESLint/Prettier config).

**README.md is stale** — trust config/code instead:

- Project is a full IoT platform (auth, invites, telemetry, calibration, relays, admin), not a single-device status monitor.
- Java 21 (README says 17+); config key is `sensoterra:` lowercase (README shows `Sensoterra:`).
- Default MQTT broker is a remote SSL broker hardcoded in `server/src/main/resources/application.yml`, not `localhost:1883`.

## Commands

```bash
docker compose up -d            # Postgres (Timescale image) — from repo root
cd server && ./gradlew build    # build + all tests
cd server && ./gradlew run      # serves :8080
cd client && npm install && npm run dev   # :3000
cd client && npm run build      # static output in client/dist/
```

- Always use `./gradlew` (plain `gradle` may not match wrapper 8.14.5). `gradle.properties` caps the build JVM at 256m / 2 workers — don't raise it casually.
- Single test: `./gradlew test --tests 'GoogleOAuthTest'`.
- Start order matters: `docker compose up -d` → `./gradlew run` (Flyway runs at startup; server fails without DB) → `npm run dev`.
- No lint / typecheck / frontend-test scripts exist — don't invent them.
- Root `.env` (gitignored) supplies `POSTGRES_DB/USER/PASSWORD/PORT` to compose; values must match `application.yml` datasource defaults (`sensoterra` / `sensoterra_secret`) or be overridden via `DB_URL` / `DB_USER` / `DB_PASSWORD`.

## Server tests

- Self-contained: H2 in PostgreSQL mode, Flyway disabled, `hbm2ddl=create` (see `server/src/test/resources/application.yml`). No Docker, MQTT broker, or network needed.
- **Tests never run Flyway migrations** — a broken migration passes `./gradlew test`. Verify migration SQL against the real docker DB when touching `db/migration/`.
- `GoogleOAuthTest` and `PasswordChangeTest` start `FakeGoogle`, an HTTPS server on fixed port **18443** — port must be free; don't run two test JVMs at once.

## Schema changes

- Production uses `hbm2ddl: none` — entity edits alone do nothing. The current fresh-install baseline is `server/src/main/resources/db/migration/V1__init.sql`; add a new `V<N>__*.sql` after the highest version for each later schema change, and never edit an already-applied migration. The former V1–V30 history is not compatible with this consolidated baseline.

## Gotchas

- Vite dev proxies **only `/api`** → `:8080` (`client/vite.config.js`). The client opens its WebSocket at same-origin `/ws/status`, which Vite does *not* proxy (code comments reference a `serve.py` that is not in the repo) — live updates won't work under plain `npm run dev` without adding a proxy entry or serving behind your own reverse proxy.
- WebSocket auth: browser can't send headers on WS, so the JWT goes as `?token=` query param; validated in `ws/DeviceStatusWebSocket.java`.
- Micronaut's built-in `/login` is disabled; login only via `POST /api/auth/login`.
- Google OAuth is off by default: requires `GOOGLE_OAUTH_ENABLED=true` + `GOOGLE_CLIENT_ID` (button hidden otherwise, exposed through `/api/auth/status`).
- MQTT broker endpoint and device/topic defaults live in `application.yml`; MQTT username/password and JWT signing secret must be provided through `SENSOTERRA_MQTT_USERNAME`, `SENSOTERRA_MQTT_PASSWORD`, and `JWT_SECRET` environment variables.
- The service worker is hand-rolled (`client/public/sw.js` + `registerSW.js` loaded from `index.html`).
- CORS (allowed origins incl. private-LAN regex) lives in `server/src/main/resources/application.yml` — keep `http://localhost:3000` allowed.
- Code/config comments are in Russian — match the existing style.
