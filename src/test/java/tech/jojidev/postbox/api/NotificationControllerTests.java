package tech.jojidev.postbox.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tech.jojidev.postbox.email.EmailNotificationService;
import tech.jojidev.postbox.push.PushService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTests {

    private final EmailNotificationService service = mock(EmailNotificationService.class);
    private final PushService push = mock(PushService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var router = new NotificationChannelRouter(List.of(new EmailChannelHandler(service), new PushChannelHandler(push)));
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(router))
                .setControllerAdvice(new NotificationExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void acceptsNotification() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(
                new EmailNotificationService.Result(id, "person@example.com", null, "QUEUED", OffsetDateTime.now()));

        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"channel":"EMAIL","recipient":"person@example.com","payload":{"name":"Person"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    void rejectsUnsupportedChannel() throws Exception {
        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"channel":"SMS","recipient":"person@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void rejectsInvalidRecipientAndBlankTemplateKey() throws Exception {
        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"channel":"EMAIL","recipient":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("recipient:")));

        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"channel":"EMAIL","recipient":"person@example.com","templateKey":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("templateKey:")));
    }

    @Test
    void rejectsMissingChannelAndNonObjectPayload() throws Exception {
        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"recipient":"person@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("channel:")));

        mvc.perform(post("/v1/notifications").contentType("application/json")
                        .content("""
                                {"channel":"EMAIL","recipient":"person@example.com","payload":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getsNotificationOrReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id, null)).thenReturn(Optional.of(
                new EmailNotificationService.Result(id, "person@example.com", null, "QUEUED", OffsetDateTime.now())));

        mvc.perform(get("/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        mvc.perform(get("/v1/notifications/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Notification not found"));

        mvc.perform(get("/v1/notifications/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
