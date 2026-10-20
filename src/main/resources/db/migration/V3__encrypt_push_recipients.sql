ALTER TABLE push_deliveries ADD COLUMN recipient_cipher TEXT;
ALTER TABLE push_deliveries ALTER COLUMN recipient DROP NOT NULL;
-- No previously stored rows may have plaintext recipients after upgrading; this removes them.
-- Operators should rotate the database backup containing any earlier development data.
UPDATE push_deliveries SET status = 'FAILED', failure_code = 'TOKEN_KEY_UNAVAILABLE',
    recipient = NULL, updated_at = NOW() WHERE recipient IS NOT NULL;
DELETE FROM push_outbox WHERE notification_id IN
    (SELECT id FROM push_deliveries WHERE failure_code = 'TOKEN_KEY_UNAVAILABLE');
