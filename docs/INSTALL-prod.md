# Production installation

Everything about running Sensoterra on the production VPS: the compose overlay, continuous deployment, the one-time server and GitHub setup, database backups and day-to-day operations. General information (local development, build, tests, configuration) is in the [README](../README.md).

## Production overlay

`docker-compose.prod.yml` is an overlay for a VPS behind nginx on the host. It runs prebuilt images from GHCR (`ghcr.io/vityusha/sensoterra-server|client:${IMAGE_TAG}`) instead of building on the VPS. It also:

- binds the client to `127.0.0.1:${APP_PORT}` only and stops publishing the database port (Docker-published ports bypass ufw); terminate TLS in the host nginx and proxy to `http://127.0.0.1:3000` (template: [`deploy/nginx-host.conf`](../deploy/nginx-host.conf));
- enables the `prod` Micronaut profile (`server/src/main/resources/application-prod.yml`): CORS disabled, since the browser only talks to one origin;
- sets `SENSOTERRA_LOG_LEVEL=INFO` (the app logger level in `logback.xml`; DEBUG by default);
- sets memory limits for a 2 vCPU / 4 GB VPS, `JAVA_OPTS`, TimescaleDB auto-tuning (applied only when the volume is first created), and Docker log rotation.

Each running server needs its own `SENSOTERRA_MQTT_CLIENT_ID`; two clients with the same ID keep disconnecting each other on the broker.

## Continuous deployment

`.github/workflows/deploy.yml` runs on every push to `main` (i.e. after a PR is merged):

1. **CI** — the same server build/tests and client build as `ci.yml` (reused via `workflow_call`).
2. **Images** — builds `server` and `client` and pushes them to GHCR tagged with the commit SHA and `latest`.
3. **Deploy** — copies `docker-compose.yml`, `docker-compose.prod.yml` and `deploy/` to the VPS over SSH and runs `deploy/deploy.sh <sha>`, which:
   - dumps the database to `backups/pre-deploy-*.dump` (keeps the last 10) — Flyway migrates on startup;
   - pulls the images and runs `docker compose up -d`;
   - waits for the server healthcheck and `GET /health` through the client (3 minutes);
   - on success writes `IMAGE_TAG=<sha>` to `.env` and removes old app images (keeps current and previous);
   - on failure rolls the containers back to the previous tag and fails the job. The database is **not** restored automatically; the log prints the dump path.

**Rollback / redeploy:** Actions → Deploy → Run workflow, with `image_tag` set to an earlier commit SHA. That skips CI and the build.

### One-time VPS setup

1. Install Docker Engine with the compose plugin, and `curl`.
2. Create a deploy user in the `docker` group and the app directory:
   ```bash
   sudo useradd -m -s /bin/bash -G docker deploy
   sudo install -d -o deploy -g deploy /opt/sensoterra
   ```
3. Put the production `.env` into `/opt/sensoterra/.env` (`chmod 600`). Use a unique `SENSOTERRA_MQTT_CLIENT_ID`, a strong `JWT_SECRET` and `POSTGRES_PASSWORD`.
4. Generate a key pair for GitHub Actions: `ssh-keygen -t ed25519 -f sensoterra-deploy -N ""`. Append `sensoterra-deploy.pub` to `/home/deploy/.ssh/authorized_keys`.
5. Configure host nginx from the template [`deploy/nginx-host.conf`](../deploy/nginx-host.conf): TLS via certbot, proxy to `http://127.0.0.1:3000`, WebSocket headers for `/ws/`, rate limiting on login, and no access log for `/ws/` (the JWT is in its query string). Installation steps are at the top of the file.
6. Set up the firewall and fail2ban — see [Firewall and fail2ban](#firewall-and-fail2ban) (after the first deploy, so the scripts are in `/opt/sensoterra/deploy/host/`).

### GitHub settings

- Settings → Environments → create `production` (optionally add required reviewers to approve each deploy). Add the environment secrets:
  - `VPS_HOST` — server address;
  - `VPS_USER` — `deploy`;
  - `VPS_SSH_KEY` — contents of the private key `sensoterra-deploy`;
  - `VPS_KNOWN_HOSTS` — output of `ssh-keyscan -p <port> <host>` (verify the fingerprint against the server).
- Optional environment variables: `VPS_PORT` (default `22`), `VPS_APP_DIR` (default `/opt/sensoterra`).
- Settings → Branches → protect `main`: require a PR and the CI checks to pass before merging.
- The first push creates the GHCR packages, linked to the repository. Deploys pull them with the workflow's own token, so they can stay private.

## Firewall and fail2ban

Only SSH, 80 and 443 are open to the internet. Everything else listens on loopback: the client container on `127.0.0.1:3000`, and the database is not published at all. That matters because Docker-published ports bypass ufw: Docker adds its own iptables rules, so a port published on `0.0.0.0` would be reachable whatever ufw says. `setup-firewall.sh` warns if a container publishes on all interfaces.

| Port | Purpose | Rule |
|---|---|---|
| SSH (`sshd -T` port, e.g. 87) | admin access, GitHub Actions deploy | `ufw limit`: at most 6 new connections per 30 s per IP; a deploy needs 2 |
| 80/tcp | redirect to HTTPS, certbot HTTP-01 | allow |
| 443/tcp | the app | allow |
| everything else | — | deny incoming, allow outgoing (MQTT broker, GHCR, restic storage are outbound) |

fail2ban jails ([`deploy/host/fail2ban/`](../deploy/host/fail2ban/)):

- **`sshd`**: 5 failed SSH logins in 10 minutes. Reads `/var/log/auth.log`, or journald where there is no rsyslog (Debian 12). The deploy key logs in on the first try, so GitHub Actions is never banned.
- **`sensoterra-auth`**: 10 failed app logins in 10 minutes. It matches `401`/`403`/`429` on `/api/auth/login`, `/oauth/google`, `/password` and `/bootstrap` in the host nginx access log. `429` means the nginx `limit_req` (5/min) already fired; fail2ban then blocks the whole IP, not just the login endpoint.
- **Ban length**: 1 hour, doubling for repeat offenders up to 1 week (`bantime.increment`). Loopback is never banned; add your own addresses with `IGNOREIP`.

The bans use the client address nginx sees (`$remote_addr`). That is correct while host nginx faces the internet directly. Behind a CDN or proxy (e.g. Cloudflare), nginx needs `real_ip` set up first, otherwise fail2ban would ban the proxy.

### Setup

Run as root from `/opt/sensoterra`. Keep the current SSH session open until a new one is confirmed to work, or have the provider's web console at hand.

```bash
sudo ./deploy/host/setup-firewall.sh            # dry run: shows the SSH port and the rules
sudo ./deploy/host/setup-firewall.sh --apply
# from a second terminal: ssh -p <port> deploy@<host> must still work
sudo IGNOREIP="<your static IP>" ./deploy/host/setup-fail2ban.sh
```

Both scripts are idempotent; re-run them after changing the templates in the repository (the deploy copies them to the VPS). `setup-fail2ban.sh` overwrites `/etc/fail2ban/jail.d/sensoterra.local`, so edit the template in the repository, not the copy on the server. It also warns if SSH password authentication is still enabled; set `PasswordAuthentication no` in sshd for key-only logins.

### Day to day

```bash
sudo ufw status numbered
sudo fail2ban-client status sshd
sudo fail2ban-client status sensoterra-auth
sudo fail2ban-client set sensoterra-auth unbanip <ip>      # unban someone
sudo fail2ban-regex /var/log/nginx/access.log /etc/fail2ban/filter.d/sensoterra-auth.conf   # test the filter
sudo tail -f /var/log/fail2ban.log
```

## Database backups

The pre-deploy dump only protects migrations, and it stays on the VPS. Daily backups go off the VPS with [restic](https://restic.net/) (run from the `restic/restic` image, nothing to install on the host):

- `deploy/backup.sh` (systemd timer, 03:30 daily): `pg_dump -Fc` → checks that the dump's table of contents is readable and contains `app_user` → `restic backup` of the dump **and `.env`** (the repository is encrypted) → `restic forget --prune` (7 daily, 4 weekly, 12 monthly) → `restic check`. The last 3 dumps also stay in `backups/daily/`.
- `deploy/restore.sh check` (systemd timer, 1st of the month): restores the latest snapshot into a temporary TimescaleDB container (same image as compose) using the TimescaleDB `pre_restore`/`post_restore` procedure, then checks Flyway history, hypertables and that `app_user` is not empty. The production database is not touched.
- Optional [healthchecks.io](https://healthchecks.io/) pings (`HEALTHCHECK_URL`, `HEALTHCHECK_RESTORE_URL`) alert you when a run fails or does not happen.

### Setup (once, on the VPS, as `deploy` in `/opt/sensoterra`)

1. Create a bucket in an S3-compatible storage (or any other restic backend) **outside this VPS provider**, with a key limited to that bucket.
2. `cp deploy/env.backup.example .env.backup && chmod 600 .env.backup`, fill in `RESTIC_REPOSITORY`, `RESTIC_PASSWORD` and the storage keys. **Save `RESTIC_PASSWORD` and the storage keys in a password manager** — without them the backups cannot be restored.
3. Create the repository and run the first backup and check by hand:
   ```bash
   ./deploy/backup.sh init
   ./deploy/backup.sh
   ./deploy/restore.sh check latest
   ```
4. Install the timers (as root):
   ```bash
   sudo cp /opt/sensoterra/deploy/systemd/sensoterra-*.{service,timer} /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now sensoterra-backup.timer sensoterra-restore-check.timer
   ```
   Logs: `journalctl -u sensoterra-backup -u sensoterra-restore-check`. Next runs: `systemctl list-timers 'sensoterra-*'`.

### Restore

```bash
./deploy/backup.sh restic snapshots           # list snapshots
./deploy/restore.sh check <snapshot|latest>   # try it in a temporary container first
./deploy/restore.sh apply <snapshot|latest|backups/...dump>
```

`apply` asks for confirmation, dumps the current database to `backups/pre-restore-*.dump`, stops the server, recreates the database, restores, verifies and starts the server again.

**New VPS** (the old one is lost):

1. Do the one-time VPS setup above except `.env` (Docker, `deploy` user, SSH key, host nginx).
2. Copy `deploy/` and `docker-compose*.yml` from a checkout to `/opt/sensoterra`, and put `.env.backup` there (from the password manager).
3. `./deploy/restore.sh env latest` extracts the backed-up `.env` to `backups/restore/env`; review it and move it to `.env` (`chmod 600`).
4. Update `VPS_HOST` / `VPS_KNOWN_HOSTS` in GitHub and run the Deploy workflow: it starts an empty database and migrates it.
5. `./deploy/restore.sh apply latest`, then install the timers again.

## Operations

All commands run on the VPS as `deploy` in `/opt/sensoterra`; `COMPOSE` below means `docker compose -f docker-compose.yml -f docker-compose.prod.yml`.

### Health check

`GET https://<domain>/health` goes host nginx → client nginx → server → database, so one request checks the whole chain:

| Response | Meaning |
|---|---|
| `200 {"status":"UP"}` | everything works, MQTT broker connected |
| `200 {"status":"DEGRADED"}` | the app works, but the MQTT broker is not connected (no live data, relay commands fail) |
| `503 {"status":"DOWN"}` | the server is up, the database is not (or the disk is almost full) |
| `502` from nginx | the client container is up, the server is not |
| no response / timeout | host nginx, the client container or the VPS is down |

- Anonymous callers get only `status`. With an `Authorization: Bearer <JWT>` header the response also lists the components (`jdbc`, `diskSpace`, `mqtt` with the broker address) — open it in the browser devtools or use `curl -H "Authorization: Bearer $TOKEN" https://<domain>/health`.
- The same endpoint drives the server's Docker healthcheck and the `deploy.sh` check. `DEGRADED` stays HTTP 200 on purpose: an unreachable broker must not roll a deploy back.
- **External monitoring:** add an HTTP monitor in UptimeRobot, Better Stack, healthchecks.io or similar for `https://<domain>/health`, alerting on non-200. To be told about MQTT outages too, add a keyword check for `"UP"`. Without one, nobody notices an outage: the Docker healthcheck alerts no one, and `restart: unless-stopped` does not restart an unhealthy container.

### Logs and log level

- Server log: `docker logs -f sensoterra-server` (Docker rotates it: 5 × 10 MB).
- The app logger level comes from `SENSOTERRA_LOG_LEVEL` in `logback.xml` (DEBUG by default, `INFO` in `docker-compose.prod.yml`). Keep it the only place for levels; don't add `logger.levels` to `application*.yml`.
- To get DEBUG temporarily, change `SENSOTERRA_LOG_LEVEL` in `docker-compose.prod.yml` on the VPS and recreate the server with `COMPOSE up -d --no-build server`. The next deploy overwrites the file, so the change is temporary.

### VPS address or SSH host key changed

The deploy job connects with `StrictHostKeyChecking yes`, so after a new IP, port or reinstalled server it fails with `Host key verification failed` / `No ED25519 host key is known for [host]:port`. Update the `production` environment secrets in GitHub:

1. `VPS_HOST` — the new address.
2. `VPS_KNOWN_HOSTS` — output of `ssh-keyscan -p <port> -t ed25519 <host>`. Compare its fingerprint (`ssh-keyscan -p <port> -t ed25519 <host> | ssh-keygen -lf -`) with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` run on the server itself (provider console or an existing trusted session). If only the IP changed, the key is the same — the old line with the new address also works.
3. Re-run the failed Deploy workflow.
