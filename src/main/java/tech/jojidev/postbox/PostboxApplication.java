package tech.jojidev.postbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PostboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(PostboxApplication.class, args);
	}

}
