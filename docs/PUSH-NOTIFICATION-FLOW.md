# Generic push notification flow for Postbox

> Implementation note: The Expo PUSH flow described below is implemented in Postbox. Swagger UI at `/swagger-ui.html` documents the current API; the README covers setup. This document is the original requirement, not the authoritative API reference.

**Request to the Postbox agent:** Build a reusable, asynchronous `PUSH` channel that any application can call to send remote mobile notifications. Implement this in Postbox, not in a client app. Start with **Expo push tokens**; keep provider-specific handling separate so other providers can be added later. This document describes the required behavior and a proposed API, not a claim that push delivery already exists.

Postbox currently persists EMAIL requests. Its work-in-progress RabbitMQ listener only logs notification IDs. Keep existing EMAIL behavior intact while building the push flow. Update the README and API documentation to reflect what is actually implemented.

## Boundary: what the caller and Postbox each do

The calling application owns its users, device-token collection and permissions, notification preferences, recipient selection, business events, message content and localization, and any in-app inbox. It submits a ready-to-send push request. Postbox does **not** need the calling application's database or user accounts.

Postbox owns validation, authentication, durable acceptance, queueing, provider submission, delivery receipts, retry/failure handling, and a way for the caller to check the outcome. Postbox's database holds delivery records, not the caller's in-app notification history.

## Proposed API contract

Accept **one request per target device token**. A client can submit separate requests for multiple devices. For example:

```http
POST /v1/notifications
Authorization: Bearer <service-credential>
Idempotency-Key: order-123-ready:device-456
Content-Type: application/json

{
  "channel": "PUSH",
  "provider": "EXPO",
  "recipient": "ExpoPushToken[example]",
  "title": "Your update is ready",
  "body": "Open the app for details.",
  "data": {
    "event_id": "order-123-ready",
    "screen": "orders",
    "item_id": "123"
  }
}
```

The exact field names are negotiable, but publish a stable, versioned request/response contract. Required capabilities:

- `channel: PUSH`, a provider/token type (explicitly or inferred), a valid target token, title, body, and an optional JSON `data` map. Forward `data` to the provider unchanged so each app can choose its own deep-link/routing keys. Validate size and supported JSON types against provider limits; reject invalid requests with useful `4xx` errors. Apply channel-specific recipient validation: Expo token rules must not reuse email validation.
- Accept a caller-provided idempotency key, unique within that authenticated client. A retry with the same key and identical request returns the existing Postbox ID/status; the same key with different content fails with a clear conflict. The key identifies one intended delivery **to one target**, not an entire multi-device campaign. Never require a token or secret inside the key.
- On successful acceptance, return `202 Accepted` (or equivalent) with a stable Postbox request ID and `QUEUED` status. This means the request is persisted for processing, **not** that a provider or device received it. Keep a documented `GET /v1/notifications/{id}` (or equivalent) so the caller can retrieve status and structured failure codes, scoped to its own requests.
- Optionally support an expiry/deadline for time-sensitive pushes: reject an already expired request and never send it after expiry. Document default behavior if no deadline is supplied.

Do not make app-specific event types, locale lists, inbox IDs, or route names mandatory. The caller owns those conventions and can place minimal routing fields in `data`.

## Delivery lifecycle

```text
Client POST -> validate/authenticate -> persist request + idempotency key -> 202 QUEUED
                                         |
                                         v
                              reliably publish to RabbitMQ
                                         |
                                         v
                              worker sends to Expo
                                         |
                                         v
                              save Expo ticket ID -> check receipt -> record final outcome
```

1. Use the existing PostgreSQL and RabbitMQ stack. Make the DB-to-broker handoff recoverable even if Postbox crashes or RabbitMQ is down immediately after the request is committed. An outbox with a retrying publisher is one possible design. The current after-commit event publisher on its own does not guarantee this. Use persistent messages, appropriate acknowledgments, and a defined terminal/dead-letter path.
2. Run sending outside the HTTP request. Store provider ticket IDs and distinguish **provider accepted** from **provider receipt confirmed**; neither proves the user saw the notification. Expose useful states such as `QUEUED`, `SENDING`, `RETRYING`, `PROVIDER_ACCEPTED`, `DELIVERED_TO_PUSH_SERVICE` (receipt confirmed), and `FAILED`. Pick final names and document their meaning.
3. Retry transient provider, network, and broker failures with bounded backoff. Mark permanent failures as terminal. Consumer redelivery must not cause an intentional second send; document the unavoidable duplicate risk if the provider accepts a request but the worker crashes before saving the result. Do not treat a missing receipt as success; check it later and eventually time out.
4. Handle Expo's invalid/unregistered-device errors distinctly. Expose a machine-readable failure code (for example `DEVICE_NOT_REGISTERED`) through status lookup so the caller can remove or deactivate its own stored token. Postbox does not need to write to client databases.
5. Document configurable Expo endpoint and optional access token, retry limits, receipt polling interval/timeout, retention of delivery records, and operational logs/metrics. Do not hard-code credentials.

## Security and privacy

Authenticate service-to-service requests and isolate each client's create, status, and idempotency records. Do not expose an unauthenticated public notification-sending API. Protect stored tokens and credentials; do not log complete push tokens, auth headers, or whole payloads. Avoid recording personal content in diagnostics. Preserve the caller's title, body, and `data` without enriching them from another app's user records.

## Acceptance criteria

- A valid Expo PUSH request can be submitted, retrieved by ID, sent by a RabbitMQ worker, and tracked through Expo ticket and receipt; an invalid request receives a clear validation error.
- Repeating an identical idempotency key cannot create another delivery; a different key for a second device can send independently; conflicting reuse fails.
- Recovery is demonstrated across a broker outage/process restart; transient provider errors retry, permanent errors end, and invalid tokens produce a structured failure status.
- API authorization/client isolation, sensitive-data-safe logging, and expiry (if supported) are tested.
- Add focused unit/integration tests and a local end-to-end test using PostgreSQL, RabbitMQ, and a mocked Expo endpoint. Document how to run them and update the README only when the behavior works.

This request is limited to **generic remote push delivery**. Email delivery, SMS, campaigns, app-specific preferences, and in-app notification screens are separate work.
