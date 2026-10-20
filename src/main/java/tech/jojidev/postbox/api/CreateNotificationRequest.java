package tech.jojidev.postbox.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tech.jojidev.postbox.common.NotificationChannel;

import java.util.Map;
import java.time.OffsetDateTime;

public record CreateNotificationRequest(
        @NotNull NotificationChannel channel,
        @Schema(description = "Email address for EMAIL; provider-specific device token for PUSH (ExpoPushToken[...] for EXPO)")
        @NotBlank @Size(max = 320) String recipient,
        @Schema(description = "EMAIL only; optional") @Size(max = 100) String templateKey,
        @Schema(description = "EMAIL only; optional JSON object") Map<String, Object> payload,
        @Schema(description = "PUSH only; registered provider identifier (currently EXPO)", example = "EXPO") String provider,
        @Schema(description = "PUSH only; required") String title,
        @Schema(description = "PUSH only; required") String body,
        @Schema(description = "PUSH only; optional JSON object") Map<String, Object> data,
        @Schema(description = "PUSH only; optional ISO-8601 deadline") OffsetDateTime expiresAt
) {
    public CreateNotificationRequest(NotificationChannel channel, String recipient, String templateKey, Map<String, Object> payload) {
        this(channel, recipient, templateKey, payload, null, null, null, null, null);
    }
}
