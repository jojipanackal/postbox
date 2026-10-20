package tech.jojidev.postbox.email;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface EmailNotificationRepository extends JpaRepository<EmailNotification, UUID> {
    Optional<EmailNotification> findByIdAndClientId(UUID id, String clientId);
}
