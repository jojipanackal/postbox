# Postbox

Postbox is a work-in-progress, reusable asynchronous notification service built with Java, Spring Boot, PostgreSQL, and RabbitMQ.

The goal is to give applications one API for sending notifications through multiple channels. Email is the first planned channel; SMS and push notifications may follow. Rather than delivering a notification during the API request, Postbox will store the request in PostgreSQL and queue delivery work in RabbitMQ. Background workers will contact providers, track delivery status, and retry transient failures.

This is a learning project focused on message queues, asynchronous processing, database migrations, retries, idempotency, and reliable delivery. **The notification API and workers are not implemented yet.**

## What exists today

- A Spring Boot application with PostgreSQL, RabbitMQ, JPA, validation, and Actuator dependencies.
- Docker Compose services for PostgreSQL and RabbitMQ.
- Flyway migration `V1__create_notifications_table.sql`, which creates a `notifications` table and a status/creation-time index.
- A Spring Boot context-loading test.

There is currently no notification endpoint or delivery worker. The Actuator health endpoint is available once the application starts; a 404 response at `/` is expected.

## Run locally

You need **JDK 25**, Docker Desktop (or another working Docker engine), and IntelliJ IDEA or a terminal. Maven is provided by `mvnw`, so a separate Maven installation is not required.

1. Create a `.env` file in the project root with your own local passwords:

   ```dotenv
   POSTGRES_PASSWORD=replace-with-a-local-password
   RABBITMQ_PASSWORD=replace-with-a-local-password
   ```

   `.env` is ignored by Git. Do not commit real credentials.

2. Start Docker, then start the dependencies from the project root:

   ```bash
   docker compose up -d
   docker compose ps
   ```

3. Open the project in IntelliJ and reload the Maven project. Set the Project SDK to JDK 25. Create an **Application** run configuration with main class `tech.jojidev.postbox.PostboxApplication` and module `postbox`. In that configuration, add `POSTGRES_PASSWORD` and `RABBITMQ_PASSWORD` as environment variables, using the same values as in `.env`. Docker Compose reads `.env`, but the IntelliJ application run configuration does not automatically pass its values to Spring Boot.

4. Run `PostboxApplication`. Check [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health). RabbitMQ's management UI is at [http://localhost:15672](http://localhost:15672) (username `postbox`, password from `.env`).

The application connects to PostgreSQL at `localhost:5433/postbox` and RabbitMQ at `localhost:5672`. Make sure `docker compose ps` shows the services as healthy before starting the app.

## Database migrations

Flyway applies pending SQL migrations from `src/main/resources/db/migration` when the application starts. When Flyway first runs against this database, `V1__create_notifications_table.sql` creates the table. You can check the migration history with:

```bash
docker exec postbox-postgres psql -U postbox -d postbox -c "SELECT version, description, success FROM flyway_schema_history;"
```

After a migration has run, do not edit it. Add the next database change in a new file such as `V2__add_notification_priority.sql`. Hibernate is configured with `ddl-auto=validate`, so schema changes should be made through migrations rather than automatic Hibernate table creation.

## Direction

Planned work includes a notification API, email delivery, RabbitMQ-backed background workers, delivery tracking, retries, and idempotent processing. SMS and push notifications are possible later channels. These are goals, not features available in the current code.
