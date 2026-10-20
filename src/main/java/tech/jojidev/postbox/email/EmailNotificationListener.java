package tech.jojidev.postbox.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationListener.class);

    @RabbitListener(queues = "postbox.notifications.created")
    public void receive(String notificationId) {
        log.info("Received notification ID: {}", notificationId);
    }
}
