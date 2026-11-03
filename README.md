# Sensoterra

Sensoterra is an IoT platform for managing devices, sensors, telemetry, calibration, relays, user access, and administration. It consists of two independently built applications:

- `server/` — Java 21, Micronaut 4, Gradle 8.14.5, PostgreSQL/TimescaleDB.
- `client/` — Vue 3, Vite 6, JavaScript.

## Requirements

- Java 21
- Node.js and npm
- Docker Compose (for the PostgreSQL/TimescaleDB database)

## Configure

Copy `.env.example` to `.env`, replace the placeholder JWT secret with a fresh random value, and provide the MQTT credentials for your broker:

```text
POSTGRES_DB=sensoterra
POSTGRES_USER=sensoterra
POSTGRES_PASSWORD=choose-a-local-database-password
POSTGRES_PORT=5432
DB_URL=jdbc:postgresql://localhost:5432/sensoterra
DB_USER=sensoterra
DB_PASSWORD=choose-a-local-database-password
JWT_SECRET=replace-with-a-random-base64-secret
SENSOTERRA_MQTT_USERNAME=
SENSOTERRA_MQTT_PASSWORD=
```

The server reads environment variables from its process. Docker Compose reads `.env` automatically for the database container; export the variables into your shell before starting the server.

PowerShell:

```powershell
Get-Content .env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item "Env:$name" $value
}
```

Bash:

```bash
set -a
. ./.env
set +a
```

Generate a JWT secret with `openssl rand -base64 32`. Keep `.env` private; it is excluded by `.gitignore`.

## Run with Docker Compose

`docker-compose.yml` runs the whole stack: TimescaleDB, the server, and the client (built and served by nginx, which also proxies `/api` and `/ws` to the server). With `.env` in place, from the repository root:

```bash
docker compose up -d --build
```

Create the first admin once the server is up (see [Admin accounts](#admin-accounts)):

```bash
docker compose exec server /app/bin/sensoterra-server admin create admin
```

The app is available at `http://localhost:3000` (change with `APP_PORT`). The compose file sets the server's database connection itself, so `DB_URL`/`DB_USER`/`DB_PASSWORD` in `.env` are only used when running the server on the host. The database port (`POSTGRES_PORT`) stays published for local development. Follow logs with `docker compose logs -f server`. `http://localhost:3000/health` reports the service status (`UP`, `DEGRADED` without MQTT, `DOWN` without the database); the Vite dev server proxies it too.

### Production

The production VPS runs prebuilt images from GHCR with the `docker-compose.prod.yml` overlay behind nginx on the host; every push to `main` is deployed by `.github/workflows/deploy.yml`, and the database is backed up daily off the VPS. Setup, deployment, backups and operations are described in [docs/INSTALL-prod.md](docs/INSTALL-prod.md).

## Run locally

Start only the database from the repository root:

```bash
docker compose up -d timescaledb
```

Start the server in a second terminal (the server runs Flyway migrations at startup):

```bash
cd server
./gradlew run          # macOS/Linux
gradlew.bat run        # Windows
```

Start the client in another terminal:

```bash
cd client
npm ci
npm run dev
```

The client is available at `http://localhost:3000`; the server listens on port `8080`. Vite proxies `/api` and the `/ws` WebSocket to the server.

Create an admin with the server's CLI (same environment variables as the server; under Gradle the password is read from stdin, twice):

```bash
cd server
./gradlew run --args="admin create admin"
```

## Admin accounts

There is no sign-up or web form for admins: they are managed only with the server CLI, which talks to the database directly (having a shell next to the server is the authority). Regular users register themselves.

```text
sensoterra-server admin create <username>          create an ADMIN (asks for the password)
sensoterra-server admin promote <username>         make an existing account ADMIN
sensoterra-server admin demote <username>          make an ADMIN a regular USER
sensoterra-server admin reset-password <username>  set a new password
sensoterra-server admin reset-2fa <username>       forget the authenticator; set up again on next sign-in
sensoterra-server admin list                       list admins and their 2FA state
```

Run it as `docker compose exec server /app/bin/sensoterra-server admin ...` or `./gradlew run --args="admin ..."`. The database schema must exist (start the server once so Flyway runs).

Every admin signs in with a second factor (TOTP): on the first sign-in the login page shows a QR code for an authenticator app (Google Authenticator, Yandex Key, Aegis, 1Password, ...) and then ten one-time recovery codes. Google/Yandex sign-in of an admin asks for the code too. Admin sessions are short: a token lives 1 hour (renewed while active) and the session ends 12 hours after sign-in. Admin sign-ins and admin actions are listed at the bottom of the users page.

For local development the second factor can be switched off with `SENSOTERRA_ADMIN_MFA=false`; the `prod` profile ignores it and refuses to start if 2FA is disabled any other way.

## Build

```bash
cd server && ./gradlew build
cd client && npm ci && npm run build
```

On Windows, use `gradlew.bat build` in `server`. The client production bundle is written to `client/dist/`.

## Tests

Server tests use H2 in PostgreSQL mode and do not need Docker or a live MQTT broker:

```bash
cd server
./gradlew test
```

`GoogleOAuthTest` and `PasswordChangeTest` use fixed port 18443 (fake Google), `YandexOAuthTest` uses 18444 (fake Yandex); do not run two test JVMs concurrently. Tests disable Flyway, so migration SQL still needs verification against a real PostgreSQL database when migrations change.

## Database migrations

The migration directory currently contains one consolidated baseline (`V1__init.sql`) for fresh databases. Existing databases with the former V1–V30 migration history are not compatible with this baseline and must be recreated or manually rebased before starting this version. After deploying the baseline, add future schema changes as new versioned migration files; do not edit an already-applied migration.

`V2__retention.sql` adds TimescaleDB data lifecycle policies:

| Data | Compressed after | Deleted after |
|---|---|---|
| `sensor_telemetry`, `block_telemetry`, `relay_event` | 7 days | 1 year |
| `automation_event` | 30 days | 1 year |
| `device_status_log` | 7 days | 90 days (the last status is kept in `device.last_status`) |
| `command_log` | — | 1 year (daily background job) |
| `sensor_telemetry_hourly` (continuous aggregate) | 7 days | 3 years |
| `sensor_telemetry_daily` (continuous aggregate) | — | kept |

Retention drops whole 7-day chunks, so the oldest raw data is up to a week older than the limit. To change a limit, add a migration that calls `remove_retention_policy` / `add_retention_policy`; keep each aggregate's refresh window shorter than the raw retention. `V2` runs outside a transaction (`V2__retention.sql.conf`) because continuous aggregates cannot be created inside one; it is idempotent, so after a failure run `flyway repair` and start the server again.

## Configuration

Database settings use `DB_URL`, `DB_USER`, and `DB_PASSWORD`. `SENSOTERRA_ADMIN_MFA=false` disables the admin second factor for local development (see [Admin accounts](#admin-accounts)); never in production. MQTT settings can be overridden with `SENSOTERRA_MQTT_BROKER`, `SENSOTERRA_MQTT_CLIENT_ID`, `SENSOTERRA_MQTT_USERNAME`, and `SENSOTERRA_MQTT_PASSWORD`. Each running server needs its own `SENSOTERRA_MQTT_CLIENT_ID`; two clients with the same ID keep disconnecting each other on the broker.

Google sign-in is disabled by default. Enable it with `GOOGLE_OAUTH_ENABLED=true` and set `GOOGLE_CLIENT_ID`.

Yandex ID sign-in is disabled by default too. Create an app at [oauth.yandex.ru](https://oauth.yandex.ru/) (platform "Web services"), grant it access to the login and the email address, and add `<site origin>/oauth/yandex` as a Redirect URI (e.g. `http://localhost:3000/oauth/yandex` for local development). Then set `YANDEX_OAUTH_ENABLED=true`, `YANDEX_CLIENT_ID` and `YANDEX_CLIENT_SECRET`. The server exchanges the code itself, so the secret stays on the server.

## Repository layout

```text
.
├── .github/workflows/      # ci.yml, deploy.yml (CD to the VPS)
├── client/                 # Vue application
├── server/                 # Micronaut application and database migrations
├── deploy/                 # VPS scripts: deploy, backup, restore, systemd units, host nginx
├── docs/INSTALL-prod.md    # Production setup and operations
├── .env.example            # Safe configuration template
├── docker-compose.yml      # TimescaleDB + server + client
├── docker-compose.prod.yml # Production overlay (VPS behind host nginx)
└── README.md
```
