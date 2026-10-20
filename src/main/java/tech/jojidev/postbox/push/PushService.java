package tech.jojidev.postbox.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PushService {
    public static class Conflict extends RuntimeException {}
    public record CreatePush(String provider, String recipient, String title, String body,
                             Map<String, Object> data, OffsetDateTime expiresAt) {}
    public record Result(UUID id, String provider, String status, String failureCode, String ticketId,
                         OffsetDateTime createdAt) {}

    private final PushDeliveryStore store;
    private final ObjectMapper json;
    private final TokenCipher tokens;
    private final PushProviderRegistry providers;

    public PushService(PushDeliveryStore store, ObjectMapper json, TokenCipher tokens,
                       PushProviderRegistry providers) {
        this.store = store;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.tokens = tokens;
        this.providers = providers;
    }

    @Transactional
    public Result create(CreatePush request, String client, String key) {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,200}")) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1-200 printable ASCII characters");
        }
        if (request.title() == null || request.title().isBlank() || request.body() == null || request.body().isBlank()) {
            throw new IllegalArgumentException("title and body are required");
        }
        Map<String, Object> data = request.data() == null ? Map.of() : request.data();
        PushProvider provider = providers.get(request.provider());
        var message = new PushProvider.Message(client, request.recipient(), request.title(), request.body(), data);
        provider.validate(message);
        try {
            String providerPayload = json.writeValueAsString(Map.of("to", request.recipient(),
                    "title", request.title(), "body", request.body(), "data", data));
            String canonical = json.writeValueAsString(Map.of("provider", provider.id(), "message", providerPayload,
                    "expiresAt", request.expiresAt() == null ? "" : request.expiresAt().toInstant().toString()));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            var prior = store.findKey(client, key);
            if (prior.isPresent()) {
                if (!hash.equals(prior.orElseThrow().requestHash())) throw new Conflict();
                return find(prior.orElseThrow().id(), client).orElseThrow();
            }
            if (request.expiresAt() != null && !request.expiresAt().isAfter(OffsetDateTime.now())) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            UUID id = UUID.randomUUID();
            boolean inserted = store.insert(id, client, key, hash, provider.id(), tokens.encrypt(request.recipient()),
                    request.title(), request.body(), json.writeValueAsString(data), request.expiresAt());
            if (!inserted) {
                var existing = store.findKey(client, key).orElseThrow();
                if (!hash.equals(existing.requestHash())) throw new Conflict();
                id = existing.id();
            }
            return find(id, client).orElseThrow();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("data must contain supported JSON values", exception);
        }
    }

    public Optional<Result> find(UUID id, String client) {
        return store.find(id, client).map(row -> new Result(row.id(), row.provider(), row.status(),
                row.failureCode(), row.ticketId(), row.createdAt()));
    }
}
