package tech.jojidev.postbox.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.jojidev.postbox.common.NotificationChannel;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class EmailNotification {

    public enum Status { QUEUED }

    @Id
    @Generated
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 320)
    private String recipient;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "template_key", length = 100)
    private String templateKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected EmailNotification() {
    }

    public EmailNotification(NotificationChannel channel, String recipient, String templateKey, Map<String, Object> payload) {
        this.channel = channel;
        this.recipient = recipient;
        this.templateKey = templateKey;
        this.payload = payload;
        this.status = Status.QUEUED;
    }

    public UUID getId() { return id; }
    public NotificationChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getTemplateKey() { return templateKey; }
    public Map<String, Object> getPayload() { return payload; }
    public Status getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
