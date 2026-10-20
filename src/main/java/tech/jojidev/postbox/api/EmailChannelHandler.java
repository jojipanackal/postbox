package tech.jojidev.postbox.api;

import org.springframework.stereotype.Component;
import tech.jojidev.postbox.common.NotificationChannel;
import tech.jojidev.postbox.email.EmailNotificationService;

import java.util.Optional;
import java.util.UUID;

@Component
public class EmailChannelHandler implements NotificationChannelHandler {
    private final EmailNotificationService service;

    public EmailChannelHandler(EmailNotificationService service) { this.service = service; }

    @Override
    public NotificationChannel channel() { return NotificationChannel.EMAIL; }

    @Override
    public Object create(CreateNotificationRequest request, String clientId, String idempotencyKey) {
        if (request.recipient() == null || !request.recipient().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("recipient: must be a valid email address");
        }
        if (request.templateKey() != null && request.templateKey().isBlank()) {
            throw new IllegalArgumentException("templateKey: must not be blank");
        }
        var command = new EmailNotificationService.CreateEmail(request.recipient(), request.templateKey(), request.payload());
        return NotificationResponse.from(service.create(command, clientId));
    }

    @Override
    public Optional<?> find(UUID id, String clientId) {
        return service.findById(id, clientId).map(NotificationResponse::from);
    }
}
