ALTER TABLE notifications ADD COLUMN client_id VARCHAR(100);
CREATE INDEX notifications_client_id_id ON notifications (client_id, id);

CREATE TABLE push_deliveries (
    id UUID PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(40) NOT NULL,
    failure_code VARCHAR(60),
    ticket_id VARCHAR(200),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT push_client_key UNIQUE (client_id, idempotency_key)
);
CREATE INDEX push_status_due ON push_deliveries (status, next_attempt_at);
CREATE INDEX push_terminal_age ON push_deliveries (status, updated_at);
CREATE TABLE push_outbox (
    notification_id UUID PRIMARY KEY REFERENCES push_deliveries(id) ON DELETE CASCADE,
    due_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempts INT NOT NULL DEFAULT 0
);
CREATE INDEX push_outbox_due ON push_outbox (due_at);
