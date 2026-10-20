package tech.jojidev.postbox.email;

import java.util.UUID;

public record EmailNotificationCreatedEvent(UUID notificationId) {
}
