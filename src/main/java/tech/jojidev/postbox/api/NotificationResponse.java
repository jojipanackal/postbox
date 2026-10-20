package tech.jojidev.postbox.api;

import tech.jojidev.postbox.common.NotificationChannel;
import tech.jojidev.postbox.email.EmailNotificationService;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(UUID id, NotificationChannel channel, String recipient, String templateKey,
                                   String status, OffsetDateTime createdAt) {

    public static NotificationResponse from(EmailNotificationService.Result result) {
        return new NotificationResponse(result.id(), NotificationChannel.EMAIL, result.recipient(),
                result.templateKey(), result.status(), result.createdAt());
    }
}
