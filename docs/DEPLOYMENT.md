# Deploy Postbox on a Linux VPS

GitHub Actions builds an image, pushes it to GHCR, uploads `compose.yaml` to the VPS, and starts Postbox. PostgreSQL and RabbitMQ run separately as shared infrastructure on the external Docker network `apps`; the Postbox deployment does not start, replace, or manage either service. The API is published only on the VPS loopback address at `127.0.0.1:8081` (`8080` is already used by `kilukkam-api`); put your existing HTTPS reverse proxy in front of it.

> For local development, use `compose.local.yaml` and the instructions in the [README](../README.md) instead. You do not need a repository checkout on the VPS; the workflow uploads `compose.yaml`.

## 1. Connect the existing PostgreSQL container

Your existing `postgres:18` container (`30a87c133a68`, named `postgres`) shows `5432/tcp`, **not** a published VPS port. Do not try to connect to it via `127.0.0.1:5432` on the VPS. On the VPS, verify it is running and healthy, then create an external Docker network if it does not already exist:

```bash
docker compose version
docker ps --filter name=^/postgres$
docker network inspect apps >/dev/null 2>&1 || docker network create apps
docker network inspect -f '{{range .Containers}}{{println .Name}}{{end}}' apps
```

If `postgres` is not listed in that last command's output, attach the **running** container without replacing it:

```bash
docker network connect apps postgres
```

If the `postbox` role/database do not already exist, create them **inside the existing container** with its administrative account (the role command prompts for a password):

```bash
docker exec -it postgres createuser -U postgres --pwprompt postbox
docker exec -it postgres createdb -U postgres -O postbox postbox
docker exec -it postgres psql -h 127.0.0.1 -U postbox -d postbox -c 'SELECT current_user, current_database();'
```

If your container's admin role is not `postgres`, use its actual admin user. If the `postbox` role/database already exist, skip creation and verify credentials with just the last command. PostgreSQL must accept TCP connections from containers on the `apps` network, and the role must own its database/schema or otherwise have permission to create tables and indexes. **Back up an existing database before first deployment or upgrades**; do not point Postbox at another application's database without reviewing the migration names and schema first. Postbox does not create the database or user automatically. Do not change the existing container's port publishing or volume.

## 2. Start shared RabbitMQ separately

RabbitMQ is **not managed by this repository**. Run it once as independent infrastructure on the VPS; the Postbox GitHub Actions workflow only manages Postbox. Its AMQP port `5672` will be accessible to containers on `apps`, **not** published to the internet; the management UI will be bound only to the VPS at `127.0.0.1:15672`. Other Docker apps can join `apps` and connect to `rabbitmq:5672` without creating another broker.

On the VPS, prepare `/opt/rabbitmq` for the `deploy` user and create the broker's own Compose file there (not in this repository):

```bash
sudo mkdir -p /opt/rabbitmq
sudo chown deploy:deploy /opt/rabbitmq
cd /opt/rabbitmq
nano compose.yaml
```

Paste this into `/opt/rabbitmq/compose.yaml`:

```yaml
name: rabbitmq
services:
  rabbitmq:
    image: rabbitmq:4-management
    container_name: rabbitmq
    restart: unless-stopped
    environment:
      RABBITMQ_DEFAULT_USER: rabbitadmin
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_ADMIN_PASSWORD:?Set RABBITMQ_ADMIN_PASSWORD in .env}
    ports:
      - "127.0.0.1:15672:15672"
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    networks:
      - apps
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
volumes:
  rabbitmq_data:
networks:
  apps:
    external: true
```

Create `/opt/rabbitmq/.env` containing `RABBITMQ_ADMIN_PASSWORD=your-strong-admin-password` and protect it with `chmod 600 /opt/rabbitmq/.env`. Use a **different** password for the Postbox application user. Start the broker as `deploy`, then create Postbox's own vhost and account (the `add_user` command prompts for its password):

```bash
cd /opt/rabbitmq
docker compose up -d --wait
docker exec rabbitmq rabbitmq-diagnostics -q ping
docker exec rabbitmq rabbitmqctl add_vhost postbox
docker exec -it rabbitmq rabbitmqctl add_user postbox
docker exec rabbitmq rabbitmqctl set_permissions -p postbox postbox '.*' '.*' '.*'
docker network inspect -f '{{range .Containers}}{{println .Name}}{{end}}' apps
```

The network output should include `postgres` and `rabbitmq`. Put the password you entered for the `postbox` user (not the admin password) in Postbox's `/var/www/postbox/.env` as `RABBITMQ_PASSWORD`. Postbox connects using the `postbox` username and vhost. Keep the `rabbitmq_data` volume across updates; changing the admin password in `.env` does **not** rotate an existing broker user's password. When another app needs RabbitMQ, give it its own user and vhost/permissions rather than reusing Postbox's credentials. `docker compose` commands under `/var/www/postbox` will not stop the broker.

## 3. Prepare Postbox secrets

Install Docker Engine and the Compose plugin, then create a directory writable by the `deploy` user:

```bash
sudo mkdir -p /var/www/postbox
sudo chown deploy:deploy /var/www/postbox
# Only if deploy cannot already run docker; log out and back in after this change:
sudo usermod -aG docker deploy
```

Create `/var/www/postbox/.env` on the VPS (never committed or uploaded by the workflow) and restrict its permissions:

```bash
cd /var/www/postbox
umask 077
nano .env
chmod 600 .env
```

Set values like these, replacing all placeholders:

```dotenv
POSTGRES_USER=postbox
POSTGRES_PASSWORD=your-existing-postbox-database-password
RABBITMQ_PASSWORD=the-postbox-user-password-entered-at-add_user
POSTBOX_CLIENTS=demo=sha256-hex-of-your-bearer-token
POSTBOX_PUSH_ENCRYPTION_KEY=base64-encoded-32-byte-key
# Optional: host loopback port; defaults to 8081 because 8080 is in use.
POSTBOX_PORT=8081
# Optional, if you use an Expo access token:
EXPO_ACCESS_TOKEN=
```

Generate a client token yourself, then hash it with `printf '%s' 'your-token' | sha256sum` (the `.env` value is the hex digest, not the token). Generate the encryption key **once** with `openssl rand -base64 32`. Keep the original bearer token in your client application's secrets. Back up the encryption key securely: losing or changing it prevents Postbox from decrypting queued PUSH device tokens. Multiple clients can be listed as comma-separated `clientId=sha256hex` entries. Never commit `.env`, and do not paste secrets into the Compose command line or deployment logs. Postbox reads this VPS-local `.env` through Compose.

If the GHCR image is private, log in **once on the VPS as `deploy`** using a GitHub token with `read:packages` access (do not store it in the repository):

```bash
docker login ghcr.io -u YOUR_GITHUB_USERNAME
```

For a public image, no GHCR login is needed.

## 4. Deploy from GitHub Actions

In this repository's **Settings → Secrets and variables → Actions**, set `VPS_HOST` (IP or hostname) and `VPS_SSH_KEY` (private SSH key for the `deploy` user). Then run **Deploy Postbox** from the **Actions** tab. The workflow builds the Java 25 image in GitHub Actions, pushes `ghcr.io/jojipanackal/postbox:latest` using `GITHUB_TOKEN`, uploads `compose.yaml` to `/var/www/postbox`, and runs `docker compose pull` followed by `up -d --wait`. No Maven or Java installation is needed on the VPS. If you fork or rename the repository, update the image name in both `.github/workflows/deploy.yml` and `compose.yaml`.

From the VPS, inspect the deployment:

```bash
cd /var/www/postbox
docker compose config --quiet
docker compose ps
docker compose logs --tail=100 app
```

`compose.yaml` joins the existing `apps` network and connects to `postgres:5432` and `rabbitmq:5672`. Start and verify the standalone broker first; Postbox's Compose file cannot wait for a service managed by another Compose project. Use `compose.local.yaml` only for local development, not together with the VPS file. If you recreate the PostgreSQL container later, attach it to `apps` again (or configure its own Compose project to join that external network).

On startup, Flyway runs the versioned SQL migrations in `src/main/resources/db/migration` (currently V1–V4) to create Postbox's tables. Hibernate only **validates** the schema; it will not create tables by itself. There is no separate migration container: if a migration fails, the new app fails to start; the workflow does **not** automatically roll back to the previous image. Because the app has no container health check, `up --wait` alone does not prove startup or migrations succeeded: check the health URL and logs below. Do not delete PostgreSQL or RabbitMQ volumes to work around migration failures.

## 5. Verify and put it behind HTTPS

From the VPS:

```bash
curl -f http://127.0.0.1:8081/actuator/health
docker exec -it postgres psql -h 127.0.0.1 -U postbox -d postbox -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

`/actuator/health` should return `UP`, and the migration query should show successful versions. A 404 at `/` is normal. If your reverse proxy runs on the VPS, proxy your HTTPS domain to `http://127.0.0.1:8081`; if your reverse proxy runs in Docker, attach it to the `apps` network and proxy to `http://app:8080` instead. Keep PostgreSQL (5432), RabbitMQ (5672/15672), and the raw app port (8081) off the public internet. Swagger UI at `/swagger-ui.html` and Actuator endpoints are not protected by the API bearer-token filter; restrict those paths at the reverse proxy if the service is internet-facing. The notification API requires `Authorization: Bearer <your-original-client-token>`; without a configured client it responds with 401.

## Updating and troubleshooting

After pushing changes, rerun **Deploy Postbox** in GitHub Actions. Keep both `.env` files, your PUSH encryption key, RabbitMQ's volume, and PostgreSQL backups across updates. Before deploying new migrations, back up the database; never edit a migration that has already run. On a 1 GB VPS already running several containers, check `free -h` and `docker stats --no-stream` before adding Java and RabbitMQ; you may need more RAM to avoid out-of-memory kills.

If the app cannot reach PostgreSQL or RabbitMQ, check `docker network inspect apps` to verify all three containers are attached, review `docker compose logs app`, and verify the credentials in the two `.env` files. If host port 8081 is occupied, set `POSTBOX_PORT` in Postbox's `.env` (the app still listens on 8080 **inside** its container) and adjust your reverse proxy and health-check URL. For broker issues use `cd /opt/rabbitmq` and `docker compose logs rabbitmq` there. A full image rebuild is not required for Postbox `.env` changes: rerun `docker compose up -d` from `/var/www/postbox` to recreate the app.
