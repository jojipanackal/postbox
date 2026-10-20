package tech.jojidev.postbox.email;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.jojidev.postbox.common.NotificationChannel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.springframework.util.StringUtils;

@Service
public class EmailNotificationService {
    public record CreateEmail(String recipient, String templateKey, Map<String, Object> payload) {}
    public record Result(UUID id, String recipient, String templateKey, String status, OffsetDateTime createdAt) {
        static Result from(EmailNotification notification) {
            return new Result(notification.getId(), notification.getRecipient(), notification.getTemplateKey(),
                    notification.getStatus().name(), notification.getCreatedAt());
        }
    }

    private final EmailNotificationRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public EmailNotificationService(EmailNotificationRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Result create(CreateEmail request, String clientId) {
        if (request.recipient() == null || !request.recipient().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || (request.templateKey() != null && !StringUtils.hasText(request.templateKey()))) {
            throw new IllegalArgumentException("Invalid email recipient or templateKey");
        }
        EmailNotification notification = new EmailNotification(NotificationChannel.EMAIL, request.recipient(), request.templateKey(),
                request.payload() == null ? Map.of() : request.payload());
        notification.setClientId(clientId);
        Result response = Result.from(repository.saveAndFlush(notification));
        eventPublisher.publishEvent(new EmailNotificationCreatedEvent(response.id()));
        return response;
    }

    @Transactional(readOnly = true)
    public Optional<Result> findById(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId).map(Result::from);
    }
}
