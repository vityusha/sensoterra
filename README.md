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

The app is available at `http://localhost:3000` (change with `APP_PORT`). The compose file sets the server's database connection itself, so `DB_URL`/`DB_USER`/`DB_PASSWORD` in `.env` are only used when running the server on the host. The database port (`POSTGRES_PORT`) stays published for local development. Follow logs with `docker compose logs -f server`.

### Production

`docker-compose.prod.yml` is an overlay for a VPS behind nginx on the host:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

On the server you can instead put `COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml` in `.env` and use plain `docker compose`. The overlay:

- binds the client to `127.0.0.1:${APP_PORT}` only and stops publishing the database port (Docker-published ports bypass ufw); terminate TLS in the host nginx and proxy to `http://127.0.0.1:3000`, including WebSocket upgrade headers for `/ws/` and `proxy_set_header Host $host`;
- enables the `prod` Micronaut profile (`server/src/main/resources/application-prod.yml`): INFO logging and CORS disabled, since the browser only talks to one origin;
- sets memory limits for a 2 vCPU / 4 GB VPS, `JAVA_OPTS`, TimescaleDB auto-tuning (applied only when the volume is first created), and Docker log rotation.

Each running server needs its own `SENSOTERRA_MQTT_CLIENT_ID`; two clients with the same ID keep disconnecting each other on the broker.

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

`GoogleOAuthTest` and `PasswordChangeTest` use fixed HTTPS port 18443; do not run two test JVMs concurrently. Tests disable Flyway, so migration SQL still needs verification against a real PostgreSQL database when migrations change.

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

Database settings use `DB_URL`, `DB_USER`, and `DB_PASSWORD`. MQTT settings can be overridden with `SENSOTERRA_MQTT_BROKER`, `SENSOTERRA_MQTT_CLIENT_ID`, `SENSOTERRA_MQTT_STATUS_TOPIC`, `SENSOTERRA_MQTT_USERNAME`, and `SENSOTERRA_MQTT_PASSWORD`. The server publishes a retained `online` to its status topic (default `sensoterra/server/status`) and registers `offline` there as its MQTT Last Will.

Google sign-in is disabled by default. Enable it with `GOOGLE_OAUTH_ENABLED=true` and set `GOOGLE_CLIENT_ID`.

## Repository layout

```text
.
├── .github/workflows/ci.yml
├── client/                 # Vue application
├── server/                 # Micronaut application and database migrations
├── .env.example            # Safe configuration template
├── docker-compose.yml      # TimescaleDB + server + client
├── docker-compose.prod.yml # Production overlay (VPS behind host nginx)
└── README.md
```
