package tech.jojidev.postbox.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PushJsonConfig {
    @Bean
    ObjectMapper pushObjectMapper() { return new ObjectMapper(); }
}
