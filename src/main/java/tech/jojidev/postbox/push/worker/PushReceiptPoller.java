package tech.jojidev.postbox.push.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.jojidev.postbox.push.PushProvider;
import tech.jojidev.postbox.push.PushProviderRegistry;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class PushReceiptPoller {
    private static final Logger log = LoggerFactory.getLogger(PushReceiptPoller.class);
    private final PushDeliveryStore store;
    private final TransactionTemplate transactions;
    private final PushProviderRegistry providers;
    private final MeterRegistry metrics;
    private final int timeoutHours;
    private final long intervalMs;

    public PushReceiptPoller(PushDeliveryStore store, TransactionTemplate transactions, PushProviderRegistry providers,
                             MeterRegistry metrics,
                             @Value("${postbox.push.receipt-timeout-hours:24}") int timeoutHours,
                             @Value("${postbox.push.receipt-interval-ms:15000}") long intervalMs) {
        this.store = store;
        this.transactions = transactions;
        this.providers = providers;
        this.metrics = metrics;
        this.timeoutHours = timeoutHours;
        this.intervalMs = intervalMs;
    }

    @Scheduled(fixedDelayString = "${postbox.push.receipt-interval-ms:15000}")
    public void checkReceipts() {
        for (UUID id : store.dueReceipts()) {
            transactions.executeWithoutResult(tx -> {
                var maybeDelivery = store.lock(id);
                if (maybeDelivery.isEmpty()) return;
                var delivery = maybeDelivery.orElseThrow();
                if (!"PROVIDER_ACCEPTED".equals(delivery.status()) || !delivery.receiptExpected() ||
                        delivery.nextAttemptAt().isAfter(OffsetDateTime.now())) return;
                if (delivery.acceptedAt().plusHours(timeoutHours).isBefore(OffsetDateTime.now())) {
                    fail(id, "RECEIPT_TIMEOUT");
                    return;
                }
                try {
                    var confirmed = providers.get(delivery.provider()).receipt(delivery.clientId(), delivery.ticketId());
                    if (confirmed == PushProvider.Receipt.CONFIRMED) {
                        store.receiptConfirmed(id);
                        metrics.counter("postbox.push.receipts.confirmed").increment();
                        return;
                    }
                } catch (PushProvider.Failure failure) {
                    if (!failure.retryable()) { fail(id, failure.code()); return; }
                } catch (IllegalArgumentException unsupported) {
                    fail(id, "PROVIDER_NOT_CONFIGURED"); return;
                }
                store.nextReceipt(id, intervalMs);
            });
        }
    }

    private void fail(UUID id, String code) {
        store.fail(id, code);
        metrics.counter("postbox.push.failures", "code", code).increment();
        log.info("Push request {} failed: {}", id, code);
    }
}
