package tech.jojidev.postbox.push.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL operations for the push delivery and its durable broker outbox. */
@Repository
public class PushDeliveryStore {
    public record KeyRecord(UUID id, String requestHash) {}
    public record Summary(UUID id, String provider, String status, String failureCode, String ticketId, OffsetDateTime createdAt) {}
    public record Delivery(String clientId, String provider, String status, String recipientCipher, String title, String body, String data,
                           String ticketId, boolean receiptExpected, int attempts, OffsetDateTime nextAttemptAt,
                           OffsetDateTime acceptedAt, OffsetDateTime expiresAt) {
        public boolean expired() {
            return expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now());
        }
    }

    private final JdbcTemplate db;

    public PushDeliveryStore(JdbcTemplate db) { this.db = db; }

    public Optional<KeyRecord> findKey(String client, String key) {
        return db.query("SELECT id, request_hash FROM push_deliveries WHERE client_id = ? AND idempotency_key = ?",
                (rs, row) -> new KeyRecord((UUID) rs.getObject("id"), rs.getString("request_hash")),
                client, key).stream().findFirst();
    }

    public Optional<Summary> find(UUID id, String client) {
        return db.query("""
                SELECT id, provider, status, failure_code, ticket_id, created_at FROM push_deliveries
                WHERE id = ? AND client_id = ?
                """, (rs, row) -> new Summary((UUID) rs.getObject("id"), rs.getString("provider"), rs.getString("status"),
                rs.getString("failure_code"), rs.getString("ticket_id"),
                rs.getObject("created_at", OffsetDateTime.class)), id, client).stream().findFirst();
    }

    public boolean insert(UUID id, String client, String key, String hash, String provider, String cipher,
                          String title, String body, String data, OffsetDateTime expiresAt) {
        int inserted = db.update("""
                INSERT INTO push_deliveries (id, client_id, idempotency_key, request_hash, provider, recipient_cipher, title, body, data, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', ?)
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """, id, client, key, hash, provider, cipher, title, body, data, expiresAt);
        if (inserted == 1) db.update("INSERT INTO push_outbox (notification_id) VALUES (?)", id);
        return inserted == 1;
    }

    /** Caller must hold a database transaction until the operation finishes. */
    public Optional<UUID> lockDueOutbox() {
        return db.query("""
                SELECT notification_id FROM push_outbox WHERE due_at <= NOW()
                ORDER BY due_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> (UUID) rs.getObject(1)).stream().findFirst();
    }

    public void markPublished(UUID id) { db.update("DELETE FROM push_outbox WHERE notification_id = ?", id); }

    public void deferPublish(UUID id) {
        db.update("""
                UPDATE push_outbox SET attempts = attempts + 1,
                due_at = NOW() + (LEAST(300, POWER(2, LEAST(attempts, 8))::int) * INTERVAL '1 second')
                WHERE notification_id = ?
                """, id);
    }

    /** Caller must hold a database transaction until the operation finishes. */
    public Optional<Delivery> lock(UUID id) {
        return db.query("SELECT * FROM push_deliveries WHERE id = ? FOR UPDATE", (rs, row) ->
                new Delivery(rs.getString("client_id"), rs.getString("provider"), rs.getString("status"), rs.getString("recipient_cipher"),
                        rs.getString("title"), rs.getString("body"), rs.getString("data"),
                        rs.getString("ticket_id"), rs.getBoolean("receipt_expected"), rs.getInt("attempts"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class),
                        rs.getObject("accepted_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)), id).stream().findFirst();
    }

    public void markSending(UUID id, int attempt) {
        db.update("UPDATE push_deliveries SET status = 'SENDING', attempts = ?, updated_at = NOW() WHERE id = ?", attempt, id);
    }

    public void providerAccepted(UUID id, String ticket, boolean receiptExpected, long pollIntervalMs) {
        db.update("""
                UPDATE push_deliveries SET status = 'PROVIDER_ACCEPTED', failure_code = NULL,
                ticket_id = ?, receipt_expected = ?, accepted_at = NOW(),
                next_attempt_at = NOW() + (INTERVAL '1 millisecond' * ?), updated_at = NOW() WHERE id = ?
                """, ticket, receiptExpected, pollIntervalMs, id);
    }

    public void fail(UUID id, String code) {
        db.update("UPDATE push_deliveries SET status = 'FAILED', failure_code = ?, updated_at = NOW() WHERE id = ?", code, id);
    }

    public void retry(UUID id, String code, int seconds) {
        db.update("""
                UPDATE push_deliveries SET status = 'RETRYING', failure_code = ?,
                next_attempt_at = NOW() + (? * INTERVAL '1 second'), updated_at = NOW() WHERE id = ?
                """, code, seconds, id);
        db.update("""
                INSERT INTO push_outbox (notification_id, due_at) VALUES (?, NOW() + (? * INTERVAL '1 second'))
                ON CONFLICT (notification_id) DO UPDATE SET due_at = EXCLUDED.due_at
                """, id, seconds);
    }

    public List<UUID> dueReceipts() {
        return db.query("""
                SELECT id FROM push_deliveries WHERE status = 'PROVIDER_ACCEPTED' AND receipt_expected = TRUE
                AND next_attempt_at <= NOW()
                ORDER BY next_attempt_at LIMIT 50
                """, (rs, row) -> (UUID) rs.getObject(1));
    }

    public void receiptConfirmed(UUID id) {
        db.update("""
                UPDATE push_deliveries SET status = 'DELIVERED_TO_PUSH_SERVICE', failure_code = NULL,
                updated_at = NOW() WHERE id = ?
                """, id);
    }

    public void nextReceipt(UUID id, long pollIntervalMs) {
        db.update("""
                UPDATE push_deliveries SET next_attempt_at = NOW() + (INTERVAL '1 millisecond' * ?),
                updated_at = NOW() WHERE id = ?
                """, pollIntervalMs, id);
    }

    public void recover(int maxAttempts) {
        db.update("""
                UPDATE push_deliveries SET status = 'RETRYING', next_attempt_at = NOW(), updated_at = NOW()
                WHERE status = 'SENDING' AND attempts < ? AND updated_at < NOW() - INTERVAL '2 minutes'
                """, maxAttempts);
        db.update("""
                UPDATE push_deliveries SET status = 'FAILED', failure_code = 'RETRY_EXHAUSTED', updated_at = NOW()
                WHERE status = 'SENDING' AND attempts >= ? AND updated_at < NOW() - INTERVAL '2 minutes'
                """, maxAttempts);
        db.update("""
                INSERT INTO push_outbox (notification_id, due_at)
                SELECT id, NOW() FROM push_deliveries d WHERE status IN ('QUEUED', 'RETRYING')
                AND next_attempt_at <= NOW() AND updated_at < NOW() - INTERVAL '1 minute'
                ON CONFLICT (notification_id) DO NOTHING
                """);
        db.update("""
                UPDATE push_deliveries SET status = 'FAILED', failure_code = 'EXPIRED', updated_at = NOW()
                WHERE status IN ('QUEUED', 'RETRYING') AND expires_at <= NOW()
                """);
        db.update("""
                DELETE FROM push_outbox o USING push_deliveries d
                WHERE o.notification_id = d.id AND d.status NOT IN ('QUEUED', 'RETRYING')
                """);
    }

    public void purge(int retentionDays) {
        db.update("""
                DELETE FROM push_deliveries WHERE (status IN ('FAILED', 'DELIVERED_TO_PUSH_SERVICE')
                OR (status = 'PROVIDER_ACCEPTED' AND receipt_expected = FALSE))
                AND updated_at < NOW() - (? * INTERVAL '1 day')
                """, retentionDays);
    }
}
