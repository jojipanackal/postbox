package tech.jojidev.postbox.api;

import tech.jojidev.postbox.common.NotificationChannel;
import tech.jojidev.postbox.push.PushService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PushNotificationResponse(UUID id, NotificationChannel channel, String provider, String status,
                                       String failureCode,
                                       @Schema(description = "Provider reference; called ticketId for v1 Expo compatibility")
                                       String ticketId, OffsetDateTime createdAt) {
    public static PushNotificationResponse from(PushService.Result result) {
        return new PushNotificationResponse(result.id(), NotificationChannel.PUSH, result.provider(), result.status(),
                result.failureCode(), result.ticketId(), result.createdAt());
    }
}
