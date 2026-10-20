package tech.jojidev.postbox.push.expo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.jojidev.postbox.push.PushProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class ExpoClient implements PushProvider {
    private static final int MAX_MESSAGE_BYTES = 4096;

    public static boolean validToken(String token) {
        return token != null && token.matches("(?:ExpoPushToken|ExponentPushToken)\\[[A-Za-z0-9_-]{1,200}\\]");
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String endpoint;
    private final String token;

    public ExpoClient(ObjectMapper json,
                      @Value("${postbox.expo.endpoint:https://exp.host/--/api/v2/push}") String endpoint,
                      @Value("${postbox.expo.access-token:}") String token) {
        this.json = json;
        this.endpoint = endpoint.replaceAll("/$", "");
        this.token = token;
    }

    @Override
    public String id() { return "EXPO"; }

    @Override
    public void validate(Message message) {
        if (!validToken(message.recipient())) {
            throw new IllegalArgumentException("recipient must be an Expo push token");
        }
        try {
            String payload = json.writeValueAsString(Map.of("to", message.recipient(),
                    "title", message.title(), "body", message.body(), "data", message.data()));
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("Expo message exceeds 4096 UTF-8 bytes");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("data must contain supported JSON values");
        }
    }

    @Override
    public Submission send(Message message) {
        JsonNode result = call("/send", Map.of("to", message.recipient(), "title", message.title(),
                "body", message.body(), "data", message.data())).path("data");
        if (result.isArray()) result = result.path(0);
        check(result);
        String id = result.path("id").asText("");
        if (id.isBlank()) throw new Failure("INVALID_PROVIDER_RESPONSE", true);
        return new Submission(id, true);
    }

    // null means the receipt is not available yet, never success.
    @Override
    public Receipt receipt(String clientId, String ticketId) {
        JsonNode result = call("/getReceipts", Map.of("ids", List.of(ticketId))).path("data").path(ticketId);
        if (result.isMissingNode() || result.isNull()) return Receipt.PENDING;
        check(result);
        return Receipt.CONFIRMED;
    }

    private void check(JsonNode result) {
        if ("ok".equals(result.path("status").asText())) return;
        String providerCode = result.path("details").path("error").asText("PROVIDER_REJECTED");
        if ("DeviceNotRegistered".equals(providerCode)) throw new Failure("DEVICE_NOT_REGISTERED", false);
        if ("MessageTooBig".equals(providerCode)) throw new Failure("MESSAGE_TOO_BIG", false);
        if ("MessageRateExceeded".equals(providerCode)) throw new Failure("RATE_LIMITED", true);
        throw new Failure("PROVIDER_REJECTED", false);
    }

    private JsonNode call(String path, Object payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() >= 500)
                throw new Failure("PROVIDER_UNAVAILABLE", true);
            if (response.statusCode() >= 400) throw new Failure("PROVIDER_AUTH_OR_REQUEST", false);
            JsonNode root = json.readTree(response.body());
            if (root.path("errors").isArray() && !root.path("errors").isEmpty())
                throw new Failure("PROVIDER_REJECTED", false);
            return root;
        } catch (Failure failure) {
            throw failure;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("PROVIDER_UNAVAILABLE", true);
        }
    }
}
