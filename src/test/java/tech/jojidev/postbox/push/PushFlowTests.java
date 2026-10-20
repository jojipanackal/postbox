package tech.jojidev.postbox.push;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import tech.jojidev.postbox.push.messaging.PushOutboxPublisher;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;
import tech.jojidev.postbox.push.worker.PushDeliveryWorker;
import tech.jojidev.postbox.push.worker.PushReceiptPoller;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Disposable PostgreSQL/RabbitMQ and a local mock Expo endpoint; no shared developer data or queues.
@Import(PushFlowTests.SampleProviderConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushFlowTests {
    @TestConfiguration
    static class SampleProviderConfig {
        @Bean
        PushProvider sampleProvider() {
            return new PushProvider() {
                public String id() { return "SAMPLE"; }
                public void validate(Message message) {
                    if (!message.recipient().startsWith("SampleToken["))
                        throw new IllegalArgumentException("Invalid SAMPLE token");
                }
                public Submission send(Message message) { return new Submission("sample-reference", false); }
            };
        }
    }
    // Keep containers alive until the test JVM exits: Spring's scheduled jobs outlive @AfterAll.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management");
    static {
        postgres.start();
        rabbitmq.start();
    }
    private static HttpServer expo;
    private static final AtomicInteger sends = new AtomicInteger();
    private static final AtomicInteger transientSends = new AtomicInteger();
    private static volatile boolean forwardedData;
    private static final Set<String> missingReceipts = ConcurrentHashMap.newKeySet();
    private static final HttpClient http = HttpClient.newHttpClient();
    // sha256("test-service-token")
    private static final String TOKEN_HASH = "a954fc0f2f00bb3a8a29a4556649ac783d8f79c67c421e4b6b75975d2f715c22";

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired PushOutboxPublisher publisher;
    @Autowired PushDeliveryWorker worker;
    @Autowired PushReceiptPoller receipts;
    @Autowired PushDeliveryStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired TokenCipher tokens;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired RabbitTemplate broker;
    private final List<UUID> createdIds = new ArrayList<>();
    private final List<UUID> emailIds = new ArrayList<>();

    @AfterEach
    void removeTestRecords() {
        for (UUID id : createdIds) db.update("DELETE FROM push_deliveries WHERE id = ?", id);
        for (UUID id : emailIds) db.update("DELETE FROM notifications WHERE id = ?", id);
    }

    private UUID recordId(HttpResponse<String> response) {
        UUID id = UUID.fromString(response.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        createdIds.add(id);
        return id;
    }

    @BeforeAll
    static void mockExpo() throws Exception {
        expo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        expo.createContext("/send", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"nested\":{\"n\":1}")) forwardedData = true;
            int count = sends.incrementAndGet();
            if (body.contains("missing-receipt")) missingReceipts.add("ticket-" + count);
            boolean unavailable = body.contains("transient") && transientSends.incrementAndGet() == 1;
            String reply = body.contains("permanent")
                    ? "{\"data\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}"
                    : "{\"data\":{\"status\":\"ok\",\"id\":\"ticket-" + count + "\"}}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        expo.createContext("/getReceipts", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ticket = body.replaceAll(".*(ticket-[0-9]+).*", "$1");
            byte[] bytes = (missingReceipts.contains(ticket) ? "{\"data\":{}}" :
                    "{\"data\":{\"" + ticket + "\":{\"status\":\"ok\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        expo.start();
    }

    @AfterAll static void stopExpo() { expo.stop(0); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("postbox.clients", () -> "client-a=" + TOKEN_HASH + ",client-b=" + TOKEN_HASH_B);
        registry.add("postbox.expo.endpoint", () -> "http://127.0.0.1:" + expo.getAddress().getPort());
        registry.add("postbox.push.receipt-interval-ms", () -> "200");
        registry.add("postbox.push.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    // sha256("second-service-token")
    private static final String TOKEN_HASH_B = "2f92a804a74b5fd99171941ab36dd3a354f3b92745b81b8625e59b50358b15b9";

    private HttpResponse<String> request(String method, String path, String body, String token, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void acceptsIsolatesAndDelivers() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = """
                {"channel":"PUSH","provider":"EXPO","recipient":"ExpoPushToken[device-a]",
                 "title":"Ready","body":"Open app","data":{"screen":"orders","nested":{"n":1}}}
                """;
        assertEquals(401, request("POST", "/v1/notifications", payload, null, key).statusCode());
        var created = request("POST", "/v1/notifications", payload, "test-service-token", key);
        assertEquals(202, created.statusCode(), created.body());
        UUID id = recordId(created);
        assertEquals(202, request("POST", "/v1/notifications", payload, "test-service-token", key).statusCode());
        assertEquals(409, request("POST", "/v1/notifications", payload.replace("Open app", "Different"),
                "test-service-token", key).statusCode());
        assertEquals(404, request("GET", "/v1/notifications/" + id, "", "second-service-token", null).statusCode());
        assertEquals(400, request("POST", "/v1/notifications", payload.replace("ExpoPushToken[device-a]", "bad"),
                "test-service-token", UUID.randomUUID().toString()).statusCode());
        String status = "";
        for (int i = 0; i < 60; i++) {
            publisher.publish();
            receipts.checkReceipts();
            status = request("GET", "/v1/notifications/" + id, "", "test-service-token", null).body();
            if (status.contains("DELIVERED_TO_PUSH_SERVICE")) break;
            Thread.sleep(100);
        }
        assertTrue(status.contains("DELIVERED_TO_PUSH_SERVICE"), status);
        assertTrue(status.contains("ticket-"), status);
        assertTrue(forwardedData, "Nested data should reach Expo unchanged");
        assertFalse(status.contains("ExpoPushToken"), status);
        assertNull(db.queryForObject("SELECT recipient FROM push_deliveries WHERE id = ?", String.class, id));
        int count = sends.get();
        worker.send(id); // redelivery must not send again
        assertEquals(count, sends.get());

        String badKey = UUID.randomUUID().toString();
        var bad = request("POST", "/v1/notifications", payload.replace("Ready", "permanent"), "test-service-token", badKey);
        UUID badId = recordId(bad);
        String failed = "";
        for (int i = 0; i < 60; i++) {
            publisher.publish();
            failed = request("GET", "/v1/notifications/" + badId, "", "test-service-token", null).body();
            if (failed.contains("DEVICE_NOT_REGISTERED")) break;
            Thread.sleep(100);
        }
        assertTrue(failed.contains("DEVICE_NOT_REGISTERED"), failed);

        var another = request("POST", "/v1/notifications", payload.replace("device-a", "device-d"),
                "test-service-token", UUID.randomUUID().toString());
        assertEquals(202, another.statusCode(), another.body());
        recordId(another);
        assertFalse(another.body().contains(id.toString()));
        var otherClient = request("POST", "/v1/notifications", payload, "second-service-token", key);
        assertEquals(202, otherClient.statusCode());
        recordId(otherClient);
    }

    @Test
    void retriesProviderAndSurvivesPublisherOutage() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = """
                {"channel":"PUSH","provider":"EXPO","recipient":"ExpoPushToken[device-b]",
                 "title":"transient","body":"Test","data":{"screen":"inbox"}}
                """;
        var created = request("POST", "/v1/notifications", payload, "test-service-token", key);
        assertEquals(202, created.statusCode(), created.body());
        UUID id = recordId(created);
        String state = "";
        for (int i = 0; i < 90; i++) {
            publisher.publish();
            receipts.checkReceipts();
            state = request("GET", "/v1/notifications/" + id, "", "test-service-token", null).body();
            if (state.contains("DELIVERED_TO_PUSH_SERVICE")) break;
            Thread.sleep(100);
        }
        assertTrue(state.contains("DELIVERED_TO_PUSH_SERVICE"), state);
        assertTrue(db.queryForObject("SELECT attempts FROM push_deliveries WHERE id = ?", Integer.class, id) >= 2);

        UUID missing = UUID.randomUUID();
        createdIds.add(missing);
        db.update("""
                INSERT INTO push_deliveries (id, client_id, idempotency_key, request_hash, recipient_cipher,
                title, body, status) VALUES (?, 'client-a', ?, 'test', ?, 'Recover', 'Test', 'QUEUED')
                """, missing, missing.toString(), tokens.encrypt("ExpoPushToken[device-c]"));
        db.update("INSERT INTO push_outbox (notification_id) VALUES (?)", missing);
        CachingConnectionFactory disconnected = new CachingConnectionFactory("127.0.0.1", 1);
        try {
            var offline = new PushOutboxPublisher(store, transactions, new RabbitTemplate(disconnected), metrics);
            offline.publish();
        } finally {
            disconnected.destroy();
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM push_outbox WHERE notification_id = ?", Integer.class, missing));
        db.update("UPDATE push_outbox SET due_at = NOW() WHERE notification_id = ?", missing);
        // A fresh publisher instance (as after a process restart) picks up the persisted outbox row.
        new PushOutboxPublisher(store, transactions, broker, metrics).publish();
        String recovered = "";
        for (int i = 0; i < 60; i++) {
            receipts.checkReceipts();
            recovered = request("GET", "/v1/notifications/" + missing, "", "test-service-token", null).body();
            if (recovered.contains("DELIVERED_TO_PUSH_SERVICE")) break;
            Thread.sleep(100);
        }
        assertTrue(recovered.contains("DELIVERED_TO_PUSH_SERVICE"), recovered);
    }

    @Test
    void rejectsExpiredRequestsAndNeverSendsAfterDeadline() throws Exception {
        String expired = """
                {"channel":"PUSH","provider":"EXPO","recipient":"ExpoPushToken[device-e]",
                 "title":"Late","body":"Test","expiresAt":"2020-01-01T00:00:00Z"}
                """;
        assertEquals(400, request("POST", "/v1/notifications", expired, "test-service-token",
                UUID.randomUUID().toString()).statusCode());

        UUID id = UUID.randomUUID();
        createdIds.add(id);
        db.update("""
                INSERT INTO push_deliveries (id, client_id, idempotency_key, request_hash, recipient_cipher,
                title, body, status, expires_at) VALUES (?, 'client-a', ?, 'test', ?, 'Late', 'Test', 'QUEUED', NOW() - INTERVAL '1 second')
                """, id, id.toString(), tokens.encrypt("ExpoPushToken[device-e]"));
        int count = sends.get();
        worker.send(id);
        assertEquals(count, sends.get());
        assertTrue(request("GET", "/v1/notifications/" + id, "", "test-service-token", null)
                .body().contains("EXPIRED"));
    }

    @Test
    void missingReceiptIsNotSuccessAndEventuallyTimesOut() throws Exception {
        String payload = """
                {"channel":"PUSH","provider":"EXPO","recipient":"ExpoPushToken[device-f]",
                 "title":"missing-receipt","body":"Test"}
                """;
        var created = request("POST", "/v1/notifications", payload, "test-service-token", UUID.randomUUID().toString());
        assertEquals(202, created.statusCode(), created.body());
        UUID id = recordId(created);
        String state = "";
        for (int i = 0; i < 60; i++) {
            publisher.publish();
            state = request("GET", "/v1/notifications/" + id, "", "test-service-token", null).body();
            if (state.contains("PROVIDER_ACCEPTED")) break;
            Thread.sleep(100);
        }
        assertTrue(state.contains("PROVIDER_ACCEPTED"), state);
        db.update("UPDATE push_deliveries SET next_attempt_at = NOW() WHERE id = ?", id);
        receipts.checkReceipts();
        assertTrue(request("GET", "/v1/notifications/" + id, "", "test-service-token", null)
                .body().contains("PROVIDER_ACCEPTED"));
        db.update("UPDATE push_deliveries SET accepted_at = NOW() - INTERVAL '25 hours', next_attempt_at = NOW() WHERE id = ?", id);
        receipts.checkReceipts();
        assertTrue(request("GET", "/v1/notifications/" + id, "", "test-service-token", null)
                .body().contains("RECEIPT_TIMEOUT"));
    }

    @Test
    void providerWithoutReceiptsStaysAcceptedAndIsNotPolled() throws Exception {
        var created = request("POST", "/v1/notifications", """
                {"channel":"PUSH","provider":"SAMPLE","recipient":"SampleToken[device]",
                 "title":"Ready","body":"Open app"}
                """, "test-service-token", UUID.randomUUID().toString());
        assertEquals(202, created.statusCode(), created.body());
        UUID id = recordId(created);
        String state = "";
        for (int i = 0; i < 60; i++) {
            publisher.publish();
            state = request("GET", "/v1/notifications/" + id, "", "test-service-token", null).body();
            if (state.contains("PROVIDER_ACCEPTED")) break;
            Thread.sleep(100);
        }
        assertTrue(state.contains("PROVIDER_ACCEPTED"), state);
        assertTrue(state.contains("\"provider\":\"SAMPLE\""), state);
        assertFalse(db.queryForObject("SELECT receipt_expected FROM push_deliveries WHERE id = ?", Boolean.class, id));
        db.update("UPDATE push_deliveries SET next_attempt_at = NOW() WHERE id = ?", id);
        receipts.checkReceipts(); // no receipt API: never claim that a device received it
        assertEquals("PROVIDER_ACCEPTED", db.queryForObject("SELECT status FROM push_deliveries WHERE id = ?", String.class, id));
    }

    @Test
    void emailRemainsPersistenceOnlyAndClientScoped() throws Exception {
        var created = request("POST", "/v1/notifications",
                "{\"channel\":\"EMAIL\",\"recipient\":\"person@example.com\"}", "test-service-token", null);
        assertEquals(202, created.statusCode(), created.body());
        UUID id = recordId(created);
        createdIds.remove(id);
        emailIds.add(id);
        assertEquals(200, request("GET", "/v1/notifications/" + id, "", "test-service-token", null).statusCode());
        assertEquals(404, request("GET", "/v1/notifications/" + id, "", "second-service-token", null).statusCode());
    }
}
