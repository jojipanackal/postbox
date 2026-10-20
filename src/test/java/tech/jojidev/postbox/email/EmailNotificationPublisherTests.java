package tech.jojidev.postbox.email;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailNotificationPublisherTests {

    @Test
    void publishesNotificationIdToExistingRoute() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        EmailNotificationPublisher publisher = new EmailNotificationPublisher(rabbitTemplate);
        UUID notificationId = UUID.randomUUID();

        publisher.publish(notificationId);

        verify(rabbitTemplate).convertAndSend("postbox.notifications", "notification.created", notificationId.toString());
    }

    @Test
    void publishesIdFromCreatedEvent() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        EmailNotificationPublisher publisher = new EmailNotificationPublisher(rabbitTemplate);
        UUID notificationId = UUID.randomUUID();

        publisher.onNotificationCreated(new EmailNotificationCreatedEvent(notificationId));

        verify(rabbitTemplate).convertAndSend("postbox.notifications", "notification.created", notificationId.toString());
    }
}
