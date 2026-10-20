CREATE TABLE notifications (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                               channel VARCHAR(20) NOT NULL,
                               recipient VARCHAR(320) NOT NULL,
                               template_key VARCHAR(100),

                               payload JSONB NOT NULL DEFAULT '{}'::jsonb,

                               status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',

                               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                               updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_status_created_at
    ON notifications (status, created_at);