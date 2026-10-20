package tech.jojidev.postbox.email;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailRabbitConfig {

    @Bean
    public DirectExchange notificationsExchange() {
        return new DirectExchange("postbox.notifications");
    }

    @Bean
    public Queue notificationsCreatedQueue() {
        return new Queue("postbox.notifications.created", true);
    }

    @Bean
    public Binding notificationsCreatedBinding(Queue notificationsCreatedQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(notificationsCreatedQueue)
                .to(notificationsExchange)
                .with("notification.created");
    }

}
