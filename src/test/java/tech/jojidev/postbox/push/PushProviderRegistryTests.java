package tech.jojidev.postbox.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PushProviderRegistryTests {
    private static PushProvider provider(String id, AtomicBoolean validated) {
        return new PushProvider() {
            public String id() { return id; }
            public void validate(Message message) { validated.set(true); }
            public Submission send(Message message) { return new Submission("provider-reference", false); }
        };
    }

    @Test
    void routesValidationAndPersistsProviderWithoutChangingPushService() {
        AtomicBoolean validated = new AtomicBoolean();
        var registry = new PushProviderRegistry(List.of(provider("SAMPLE", validated)));
        var store = mock(PushDeliveryStore.class);
        var cipher = new TokenCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var service = new PushService(store, new ObjectMapper(), cipher, registry);
        when(store.findKey(eq("client-a"), eq("request-1"))).thenReturn(Optional.empty());
        when(store.insert(any(UUID.class), eq("client-a"), eq("request-1"), anyString(), eq("SAMPLE"),
                anyString(), eq("Title"), eq("Body"), anyString(), isNull())).thenReturn(true);
        when(store.find(any(UUID.class), eq("client-a"))).thenAnswer(invocation -> Optional.of(
                new PushDeliveryStore.Summary(invocation.getArgument(0), "SAMPLE", "QUEUED", null,
                        null, OffsetDateTime.now())));

        var result = service.create(new PushService.CreatePush("SAMPLE", "sample-token", "Title", "Body",
                Map.of("screen", "orders"), null), "client-a", "request-1");

        assertTrue(validated.get());
        assertEquals("SAMPLE", result.provider());
        verify(store).insert(any(UUID.class), eq("client-a"), eq("request-1"), anyString(), eq("SAMPLE"),
                anyString(), eq("Title"), eq("Body"), anyString(), isNull());
    }

    @Test
    void rejectsUnknownAndDuplicateProviderIds() {
        var registry = new PushProviderRegistry(List.of(provider("EXPO", new AtomicBoolean())));
        assertThrows(IllegalArgumentException.class, () -> registry.get("FCM"));
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
        assertThrows(IllegalStateException.class, () -> new PushProviderRegistry(List.of(
                provider("EXPO", new AtomicBoolean()), provider("EXPO", new AtomicBoolean()))));
    }
}
