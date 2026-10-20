package tech.jojidev.postbox.push.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.jojidev.postbox.push.TokenCipher;
import tech.jojidev.postbox.push.PushProvider;
import tech.jojidev.postbox.push.PushProviderRegistry;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class PushDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(PushDeliveryWorker.class);
    private final PushDeliveryStore store;
    private final TransactionTemplate transactions;
    private final PushProviderRegistry providers;
    private final TokenCipher tokens;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    private final long pollIntervalMs;

    public PushDeliveryWorker(PushDeliveryStore store, TransactionTemplate transactions, PushProviderRegistry providers,
                              TokenCipher tokens, ObjectMapper json, MeterRegistry metrics,
                              @Value("${postbox.push.max-attempts:5}") int maxAttempts,
                              @Value("${postbox.push.receipt-interval-ms:15000}") long pollIntervalMs) {
        this.store = store;
        this.transactions = transactions;
        this.providers = providers;
        this.tokens = tokens;
        this.json = json;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.pollIntervalMs = pollIntervalMs;
    }

    public void send(UUID id) {
        // The row lock prevents simultaneous workers from intentionally sending twice.
        transactions.executeWithoutResult(tx -> {
            var maybeDelivery = store.lock(id);
            if (maybeDelivery.isEmpty()) return;
            var delivery = maybeDelivery.orElseThrow();
            if (!("QUEUED".equals(delivery.status()) || "RETRYING".equals(delivery.status())) ||
                    delivery.nextAttemptAt().isAfter(OffsetDateTime.now())) return;
            if (delivery.expired()) { fail(id, "EXPIRED"); return; }
            int attempt = delivery.attempts() + 1;
            store.markSending(id, attempt);
            try {
                if (delivery.expired()) { fail(id, "EXPIRED"); return; }
                PushProvider provider = providers.get(delivery.provider());
                Map<String, Object> data = json.readValue(delivery.data(), new TypeReference<>() {});
                var message = new PushProvider.Message(delivery.clientId(), tokens.decrypt(delivery.recipientCipher()),
                        delivery.title(), delivery.body(), data);
                var submission = provider.send(message);
                store.providerAccepted(id, submission.reference(), submission.receiptExpected(), pollIntervalMs);
                metrics.counter("postbox.push.tickets").increment();
            } catch (TokenCipher.KeyUnavailable unavailable) {
                fail(id, "TOKEN_KEY_UNAVAILABLE");
            } catch (PushProvider.Failure failure) {
                retryOrFail(id, attempt, failure);
            } catch (IllegalArgumentException unsupported) {
                fail(id, "PROVIDER_NOT_CONFIGURED");
            } catch (Exception failure) {
                retryOrFail(id, attempt, new PushProvider.Failure("PROVIDER_UNAVAILABLE", true));
            }
        });
    }

    private void fail(UUID id, String code) {
        store.fail(id, code);
        metrics.counter("postbox.push.failures", "code", code).increment();
        log.info("Push request {} failed: {}", id, code);
    }

    private void retryOrFail(UUID id, int attempt, PushProvider.Failure failure) {
        if (!failure.retryable() || attempt >= maxAttempts) {
            fail(id, failure.retryable() ? "RETRY_EXHAUSTED" : failure.code());
            return;
        }
        int delay = Math.min(300, 1 << Math.min(8, attempt));
        metrics.counter("postbox.push.send.retries").increment();
        store.retry(id, failure.code(), delay);
    }
}
