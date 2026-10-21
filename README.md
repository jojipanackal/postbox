# Postbox

Postbox is a work-in-progress, reusable asynchronous notification service built with Java, Spring Boot, PostgreSQL, and RabbitMQ.

The goal is to give applications one API for sending notifications through multiple channels. Postbox persists EMAIL requests without sending email, and asynchronously submits PUSH requests to Expo and checks their receipts. SMS is not implemented.

Postbox began as a learning project focused on message queues, asynchronous processing, migrations, retries and idempotency. The package boundaries and tests support further production hardening; they do not by themselves certify a production deployment. Review authentication, public Actuator/OpenAPI exposure, backup/restore of the token encryption key, capacity and failure recovery before launch.

## What exists today

- A Spring Boot application with PostgreSQL, RabbitMQ, JPA, validation, and Actuator dependencies.
- Docker Compose configurations for local PostgreSQL and RabbitMQ, or RabbitMQ alone when PostgreSQL is already available.
- Flyway migration `V1__create_notifications_table.sql`, which creates a `notifications` table and a status/creation-time index.
- A notification API that validates and persists EMAIL requests, and retrieves them by ID.
- An authenticated, client-scoped Expo PUSH API: one request per device, idempotency, encrypted tokens, persistent outbox, RabbitMQ worker, bounded retries, and receipt polling (Flyway V2/V3).
- Provider and channel routing boundaries: each PUSH record stores its provider (Flyway V4), and channel/provider handlers are registered independently. Only Expo is registered in the application today.
- Focused API/service tests, a PostgreSQL-backed repository test, and a Spring Boot context-loading test.

EMAIL is still persistence-only: its existing RabbitMQ listener merely logs IDs, and its after-commit publish is **not** a reliable delivery handoff. For PUSH, `QUEUED` means durably stored, not received by Expo or a device. The API contract is available in Swagger UI at `/swagger-ui.html`. The Actuator health endpoint is available once the application starts; a 404 response at `/` is expected.

Code layout: top-level `api` owns the versioned HTTP contract; `email` owns EMAIL persistence and its existing RabbitMQ logging flow; `push` owns PUSH acceptance, with `push/persistence` for PostgreSQL, `push/messaging` for RabbitMQ and the outbox publisher, `push/worker` for delivery and receipts, and `push/expo` for provider HTTP. `common` holds the shared channel enum, `security` authenticates callers, and `config` holds application-wide configuration. Java packages do not change the API routes, database tables or RabbitMQ queue names.

To add a PUSH provider, implement and register `PushProvider` (token/payload validation, submission, and optional receipt lookup). Postbox saves the provider ID with each request, so the same worker routes retries and receipts to the correct integration without a new provider-specific database table. A provider without receipts remains at `PROVIDER_ACCEPTED` and is purged after the configured retention period; it is **not** reported as device-delivered. To add another channel, register a `NotificationChannelHandler` and extend the request/response contract, its enum, and its own persistence/worker as needed. Neither FCM nor SMS is implemented. Provider credentials are currently global for Expo; onboarding service clients via `POSTBOX_CLIENTS` still requires a restart.

## Run locally

You need **JDK 25**, Docker Desktop (or another working Docker engine), and IntelliJ IDEA or a terminal. Maven is provided by `mvnw`, so a separate Maven installation is not required.

1. Create a `.env` file in the project root with your own local passwords and service credentials:

   ```dotenv
   POSTGRES_PASSWORD=replace-with-a-local-password
    RABBITMQ_PASSWORD=replace-with-a-local-password
    POSTBOX_CLIENTS=demo=replace-with-sha256-hex-of-your-service-token
    POSTBOX_PUSH_ENCRYPTION_KEY=replace-with-base64-encoded-32-byte-key
   ```

    Generate the credential hash with `printf '%s' 'your-secret' | shasum -a 256` and the key with `openssl rand -base64 32`. Set `POSTBOX_CLIENTS=demo=<hash>` and present `Authorization: Bearer your-secret` to the API. `.env` is ignored by Git; never commit credentials or lose the encryption key while records still need delivery. Multiple clients use comma-separated `clientId=hash` entries; adding or rotating a client credential currently requires changing the application environment and restarting Postbox. With no configured clients, the notification API returns 401.

2. Start Docker, then start both local dependencies from the project root:

    ```bash
    docker compose -f compose.local.yaml up -d
    docker compose -f compose.local.yaml ps
    ```

3. To run from a terminal, export the passwords from `.env` for the application, then start Spring Boot:

   ```bash
   set -a
   source .env
   set +a
   ./mvnw spring-boot:run
   ```

    Alternatively, open the project in IntelliJ and reload the Maven project. Set the Project SDK to JDK 25. Create an **Application** run configuration with main class `tech.jojidev.postbox.PostboxApplication` and module `postbox`. Set `POSTGRES_PASSWORD`, `RABBITMQ_PASSWORD`, `POSTBOX_CLIENTS`, and `POSTBOX_PUSH_ENCRYPTION_KEY` as environment variables there. Docker Compose reads `.env`, but the IntelliJ application run configuration does not automatically pass its values to Spring Boot.

4. Check [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health). RabbitMQ's management UI is at [http://localhost:15672](http://localhost:15672) (username `postbox`, password from `.env`).

Swagger UI is at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html), and the OpenAPI JSON is at [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs). In Swagger UI, click **Authorize** and enter your configured service credential (the token, not its SHA-256 hash) before trying the notification endpoints. The documentation page is public, but notification requests still require Bearer authentication; restrict access to the docs at your network edge if needed.

The application connects to PostgreSQL at `localhost:5433/postbox` and RabbitMQ at `localhost:5672`. Make sure `docker compose -f compose.local.yaml ps` shows the services as healthy before starting the app.

For a Dockerized deployment on a VPS with an existing PostgreSQL container and standalone RabbitMQ, see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). The default `compose.yaml` runs only Postbox on a shared Docker network with those services; configure production RabbitMQ independently on the VPS. For local development, continue to use `compose.local.yaml` instead.

## Try the notification API

With the app running, create a notification from another terminal:

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H 'Authorization: Bearer your-secret' \
  -H 'Content-Type: application/json' \
  -d '{"channel":"EMAIL","recipient":"person@example.com","templateKey":"welcome","payload":{"name":"Person"}}'
```

This returns **202 Accepted** with an `id`, `status` of `QUEUED`, and `createdAt`. `templateKey` and `payload` are optional; when omitted, the payload is stored as `{}`. Copy the returned ID to look it up:

```bash
curl -i -H 'Authorization: Bearer your-secret' http://localhost:8080/v1/notifications/PASTE_ID_HERE
```

An existing ID returns **200 OK** to its client; a missing or another client's ID returns **404 Not Found**. Invalid input returns **400 Bad Request**. No email is sent: its work-in-progress RabbitMQ listener only logs the ID.

For a ready-to-send Expo push (one request per device token), provide an idempotency key:

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H 'Authorization: Bearer your-secret' \
  -H 'Idempotency-Key: order-123:device-456' \
  -H 'Content-Type: application/json' \
  -d '{"channel":"PUSH","provider":"EXPO","recipient":"ExpoPushToken[example]","title":"Update ready","body":"Open the app","data":{"screen":"orders"}}'
```

The server returns 202 with its ID and current status. Use authenticated `GET /v1/notifications/{id}` for the ticket, receipt and structured failure status. The sample token is not an actual device token. Use Swagger UI for endpoint schemas and `src/main/resources/application.properties` for configurable settings.

## Run tests

The service/controller and token-cipher unit tests do not need a database. The EMAIL repository and context tests **do** need a running PostgreSQL service and the environment variables above. `PushFlowTests` uses disposable PostgreSQL and RabbitMQ Testcontainers plus a local mock Expo server, so it needs Docker but does not touch the developer's database or queues. For the complete suite, start the local services with `docker compose -f compose.local.yaml up -d`, then use:

```bash
set -a
source .env
set +a
./mvnw test
```

The repository test rolls back its insert; push integration tests use disposable containers. To run just database-independent tests, use `./mvnw -Dtest=EmailNotificationServiceTests,NotificationControllerTests,EmailNotificationPublisherTests,TokenCipherTests test`. Run the isolated end-to-end test with `./mvnw -Dtest=PushFlowTests test` with Docker running; no `.env` or shared services are needed for that test.

## Database migrations

Flyway applies pending SQL migrations from `src/main/resources/db/migration` when the application starts. When Flyway first runs against this database, `V1__create_notifications_table.sql` creates the table. You can check the migration history with:

```bash
docker exec postbox-postgres psql -U postbox -d postbox -c "SELECT version, description, success FROM flyway_schema_history;"
```

After a migration has run, do not edit it. Add the next database change in a new V5 migration. Hibernate is configured with `ddl-auto=validate`, so schema changes should be made through migrations rather than automatic Hibernate table creation.

## Direction

Email delivery, SMS and additional push providers remain future work. PUSH delivery is a provider submission and receipt-confirmation service, not an in-app inbox, campaign engine, or proof that a person saw a notification.
