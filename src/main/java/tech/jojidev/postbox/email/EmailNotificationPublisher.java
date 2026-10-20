package tech.jojidev.postbox.email;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Component
public class EmailNotificationPublisher {

    private final RabbitTemplate rabbitTemplate;

    public EmailNotificationPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(UUID notificationId) {
        rabbitTemplate.convertAndSend("postbox.notifications", "notification.created", notificationId.toString());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(EmailNotificationCreatedEvent event) {
        publish(event.notificationId());
    }
}
