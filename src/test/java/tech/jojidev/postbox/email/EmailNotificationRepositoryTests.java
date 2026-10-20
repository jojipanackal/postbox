package tech.jojidev.postbox.email;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.jojidev.postbox.common.NotificationChannel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Requires the local PostgreSQL service and POSTGRES_PASSWORD; rolls back its insert.
@SpringBootTest
@Transactional
class EmailNotificationRepositoryTests {

    @Autowired
    private EmailNotificationRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsJsonbAndReadsDatabaseGeneratedValues() {
        EmailNotification notification = repository.saveAndFlush(new EmailNotification(NotificationChannel.EMAIL,
                "person@example.com", "welcome", Map.of("name", "Person")));

        assertNotNull(notification.getId());
        assertNotNull(notification.getCreatedAt());
        assertNotNull(notification.getUpdatedAt());
        assertEquals(EmailNotification.Status.QUEUED, notification.getStatus());

        entityManager.clear();
        EmailNotification stored = repository.findById(notification.getId()).orElseThrow();
        assertEquals(Map.of("name", "Person"), stored.getPayload());
        assertEquals("welcome", stored.getTemplateKey());
    }
}
