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

## Run locally

Start the database from the repository root:

```bash
docker compose up -d
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

The client is available at `http://localhost:3000`; the server listens on port `8080`. Vite proxies `/api` to the server. Its development server does not proxy `/ws/status`, so live WebSocket updates require a reverse proxy or a separately configured WebSocket proxy.

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

## Configuration

Database settings use `DB_URL`, `DB_USER`, and `DB_PASSWORD`. MQTT settings can be overridden with `SENSOTERRA_MQTT_BROKER`, `SENSOTERRA_MQTT_CLIENT_ID`, `SENSOTERRA_MQTT_DEVICE_ID`, `SENSOTERRA_MQTT_TOPIC`, `SENSOTERRA_MQTT_USERNAME`, and `SENSOTERRA_MQTT_PASSWORD`.

Google sign-in is disabled by default. Enable it with `GOOGLE_OAUTH_ENABLED=true` and set `GOOGLE_CLIENT_ID`.

## Repository layout

```text
.
├── .github/workflows/ci.yml
├── client/                 # Vue application
├── server/                 # Micronaut application and database migrations
├── .env.example            # Safe configuration template
├── docker-compose.yml      # Local PostgreSQL/TimescaleDB
└── README.md
```
