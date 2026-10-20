package tech.jojidev.postbox.api;

import org.springframework.stereotype.Component;
import tech.jojidev.postbox.common.NotificationChannel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class NotificationChannelRouter {
    private final Map<NotificationChannel, NotificationChannelHandler> handlers;

    public NotificationChannelRouter(List<NotificationChannelHandler> implementations) {
        handlers = implementations.stream().collect(Collectors.toUnmodifiableMap(
                NotificationChannelHandler::channel, Function.identity()));
    }

    public Object create(CreateNotificationRequest request, String client, String key) {
        if (request.channel() == null) throw new IllegalArgumentException("Unsupported notification channel");
        var handler = handlers.get(request.channel());
        if (handler == null) throw new IllegalArgumentException("Unsupported notification channel");
        return handler.create(request, client, key);
    }

    public Optional<?> find(UUID id, String client) {
        for (NotificationChannelHandler handler : handlers.values()) {
            var result = handler.find(id, client);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
