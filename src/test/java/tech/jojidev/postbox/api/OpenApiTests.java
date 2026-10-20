package tech.jojidev.postbox.api;

import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tech.jojidev.postbox.config.OpenApiConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class})
class OpenApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationChannelRouter channels;

    @Test
    void documentsNotificationRoutesAndBearerAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/notifications'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/v1/notifications'].post.responses['202'].content['application/json'].schema.oneOf.length()").value(2))
                .andExpect(jsonPath("$.paths['/v1/notifications'].post.parameters[0].name").value("Idempotency-Key"))
                .andExpect(jsonPath("$.paths['/v1/notifications/{id}'].get.responses['200']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
    }
}
