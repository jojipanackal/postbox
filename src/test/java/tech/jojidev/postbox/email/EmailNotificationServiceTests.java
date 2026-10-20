package tech.jojidev.postbox.email;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tech.jojidev.postbox.common.NotificationChannel;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationServiceTests {

    private final EmailNotificationRepository repository = mock(EmailNotificationRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final EmailNotificationService service = new EmailNotificationService(repository, eventPublisher);

    @Test
    void createsQueuedNotificationWithEmptyDefaultPayload() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        when(repository.saveAndFlush(any(EmailNotification.class))).thenAnswer(invocation -> {
            EmailNotification notification = invocation.getArgument(0);
            assertEquals(Map.of(), notification.getPayload());
            assertEquals(EmailNotification.Status.QUEUED, notification.getStatus());
            ReflectionTestUtils.setField(notification, "id", id);
            ReflectionTestUtils.setField(notification, "createdAt", createdAt);
            return notification;
        });

        var response = service.create(new EmailNotificationService.CreateEmail("person@example.com", null, null), "client-a");

        assertEquals(id, response.id());
        assertEquals("QUEUED", response.status());
        assertEquals(createdAt, response.createdAt());
        verify(eventPublisher).publishEvent(new EmailNotificationCreatedEvent(id));
    }

    @Test
    void retrievesExistingNotification() {
        UUID id = UUID.randomUUID();
        EmailNotification notification = new EmailNotification(NotificationChannel.EMAIL, "person@example.com", "welcome",
                Map.of("name", "Person"));
        ReflectionTestUtils.setField(notification, "id", id);
        when(repository.findByIdAndClientId(id, "client-a")).thenReturn(Optional.of(notification));

        var response = service.findById(id, "client-a").orElseThrow();

        assertEquals(id, response.id());
        assertEquals("welcome", response.templateKey());
    }

    @Test
    void returnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndClientId(id, "client-a")).thenReturn(Optional.empty());

        assertTrue(service.findById(id, "client-a").isEmpty());
    }

    @Test
    void rejectsInvalidEmailBeforeSaving() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new EmailNotificationService.CreateEmail("invalid", null, null), "client-a"));
        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
