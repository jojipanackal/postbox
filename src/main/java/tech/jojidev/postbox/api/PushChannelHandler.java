package tech.jojidev.postbox.api;

import org.springframework.stereotype.Component;
import tech.jojidev.postbox.common.NotificationChannel;
import tech.jojidev.postbox.push.PushService;

import java.util.Optional;
import java.util.UUID;

@Component
public class PushChannelHandler implements NotificationChannelHandler {
    private final PushService service;

    public PushChannelHandler(PushService service) { this.service = service; }

    @Override
    public NotificationChannel channel() { return NotificationChannel.PUSH; }

    @Override
    public Object create(CreateNotificationRequest request, String clientId, String idempotencyKey) {
        if (request.templateKey() != null || request.payload() != null) {
            throw new IllegalArgumentException("PUSH uses data, not EMAIL templateKey or payload");
        }
        var command = new PushService.CreatePush(request.provider(), request.recipient(),
                request.title(), request.body(), request.data(), request.expiresAt());
        return PushNotificationResponse.from(service.create(command, clientId, idempotencyKey));
    }

    @Override
    public Optional<?> find(UUID id, String clientId) {
        return service.find(id, clientId).map(PushNotificationResponse::from);
    }
}
