# Project guidance for coding agents

Postbox is a beginner's Java/Spring Boot learning project. Keep explanations approachable, explain why changes are necessary, and prefer small, verifiable steps over building the entire roadmap at once.

## Current state and goal

- Read `README.md` for the project goal and local setup. The notification API currently persists and retrieves EMAIL requests; it does not deliver messages or publish to RabbitMQ. Do not describe provider integrations, queue workers, retries, or idempotency as implemented until they exist in code.
- Current application entry point: `src/main/java/tech/jojidev/postbox/PostboxApplication.java`.
- Build: Maven wrapper (`./mvnw`), Spring Boot 4.1.1, Java 25.
- Local services: `compose.local.yaml` runs PostgreSQL on host port 5433 and RabbitMQ on host ports 5672 and 15672; default `compose.yaml` runs only RabbitMQ for servers with an existing PostgreSQL instance.
- Configuration: `src/main/resources/application.properties` reads database and RabbitMQ passwords from `POSTGRES_PASSWORD` and `RABBITMQ_PASSWORD`.

## Changes and verification

- Follow existing Java package `tech.jojidev.postbox` and the project's Maven conventions. Prefer Spring Boot starters for integrations so their Spring Boot 4 auto-configuration is included.
- Put database changes in versioned Flyway migrations under `src/main/resources/db/migration`. Do not rewrite a migration that may already have been applied; add the next version. Keep Hibernate schema handling at `validate` unless there is an explicit reason to change it.
- Never commit `.env`, actual passwords, or machine-specific IntelliJ settings. Docker Compose reads `.env` automatically; the IntelliJ app run configuration needs its own environment variables.
- Add focused tests for new behavior. The existing `@SpringBootTest` and the notification repository test require PostgreSQL and environment variables; do not present them as isolated unit tests. The notification service and controller tests run without a database.
- When verifying a change, report what was actually run. Avoid wiping Docker volumes or resetting a developer's database to resolve migration errors without explicit permission.
