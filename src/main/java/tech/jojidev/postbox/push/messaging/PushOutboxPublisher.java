package tech.jojidev.postbox.push.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

@Component
public class PushOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(PushOutboxPublisher.class);
    private final PushDeliveryStore store;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;

    public PushOutboxPublisher(PushDeliveryStore store, TransactionTemplate transactions,
                               RabbitTemplate rabbit, MeterRegistry metrics) {
        this.store = store;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${postbox.push.publish-interval-ms:2000}")
    public void publish() {
        // Lock + broker confirm: a crash after confirm can duplicate a message, not an intentional send.
        for (int i = 0; i < 20; i++) {
            try {
                Boolean found = transactions.execute(tx -> {
                    var maybeId = store.lockDueOutbox();
                    if (maybeId.isEmpty()) return false;
                    var id = maybeId.orElseThrow();
                    try {
                        rabbit.invoke(ops -> {
                            ops.convertAndSend(PushRabbitConfig.EXCHANGE, "send", id.toString(), message -> {
                                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                                return message;
                            });
                            ops.waitForConfirmsOrDie(5000);
                            return null;
                        });
                        store.markPublished(id);
                    } catch (Exception unavailable) {
                        metrics.counter("postbox.push.publish.retries").increment();
                        log.warn("Push broker publish deferred for request {}", id);
                        store.deferPublish(id);
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(found)) break;
            } catch (Exception databaseUnavailable) {
                break; // next scheduled run retries without logging tokens or payloads.
            }
        }
    }
}
