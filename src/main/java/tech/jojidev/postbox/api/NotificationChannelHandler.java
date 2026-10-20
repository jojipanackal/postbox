package tech.jojidev.postbox.api;

import tech.jojidev.postbox.common.NotificationChannel;

import java.util.Optional;
import java.util.UUID;

/** HTTP-boundary adapter for a notification channel; business services stay independent of API DTOs. */
public interface NotificationChannelHandler {
    NotificationChannel channel();
    Object create(CreateNotificationRequest request, String clientId, String idempotencyKey);
    Optional<?> find(UUID id, String clientId);
}
