package tech.jojidev.postbox.push.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tech.jojidev.postbox.push.worker.PushDeliveryWorker;
import java.util.UUID;

@Component
public class PushListener {
    private final PushDeliveryWorker worker;

    public PushListener(PushDeliveryWorker worker) { this.worker = worker; }

    @RabbitListener(queues = PushRabbitConfig.SEND_QUEUE)
    public void receive(String id) {
        worker.send(UUID.fromString(id));
    }
}
