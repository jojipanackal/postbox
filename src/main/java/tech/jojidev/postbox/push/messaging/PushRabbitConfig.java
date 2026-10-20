package tech.jojidev.postbox.push.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PushRabbitConfig {
    public static final String EXCHANGE = "postbox.push";
    public static final String SEND_QUEUE = "postbox.push.send";
    public static final String DEAD_EXCHANGE = "postbox.push.dead";

    @Bean
    public DirectExchange pushExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean
    public DirectExchange pushDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }

    @Bean
    public Queue pushQueue() {
        return QueueBuilder.durable(SEND_QUEUE).deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("dead").build();
    }

    @Bean
    public Queue pushDeadQueue() { return QueueBuilder.durable(DEAD_EXCHANGE).build(); }

    @Bean
    public Binding pushBinding(Queue pushQueue, DirectExchange pushExchange) {
        return BindingBuilder.bind(pushQueue).to(pushExchange).with("send");
    }

    @Bean
    public Binding pushDeadBinding(Queue pushDeadQueue, DirectExchange pushDeadExchange) {
        return BindingBuilder.bind(pushDeadQueue).to(pushDeadExchange).with("dead");
    }
}
