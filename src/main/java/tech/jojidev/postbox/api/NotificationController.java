package tech.jojidev.postbox.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.servlet.http.HttpServletRequest;
import tech.jojidev.postbox.api.NotificationExceptionHandler.ErrorResponse;

import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationChannelRouter channels;

    public NotificationController(NotificationChannelRouter channels) {
        this.channels = channels;
    }

    @PostMapping
    @Operation(summary = "Create an EMAIL or Expo PUSH notification",
            description = "EMAIL is stored but not delivered. PUSH requires an Idempotency-Key and is processed asynchronously.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Notification accepted",
                     content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {NotificationResponse.class, PushNotificationResponse.class}))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid service credential"),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused for different PUSH content",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Push encryption key unavailable",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> create(@Valid @RequestBody CreateNotificationRequest request,
                                    @Parameter(description = "Required for PUSH: 1–200 printable ASCII characters; omit for EMAIL")
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Parameter(hidden = true) HttpServletRequest servletRequest) {
        String client = (String) servletRequest.getAttribute("clientId");
        return ResponseEntity.accepted().body(channels.create(request, client, key));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a notification belonging to the authenticated client")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification found",
                     content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {NotificationResponse.class, PushNotificationResponse.class}))),
            @ApiResponse(responseCode = "400", description = "Invalid notification ID", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid service credential"),
            @ApiResponse(responseCode = "404", description = "Not found or belongs to another client",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> get(@PathVariable UUID id, @Parameter(hidden = true) HttpServletRequest servletRequest) {
        String client = (String) servletRequest.getAttribute("clientId");
        return channels.find(id, client)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Notification not found")));
    }
}
