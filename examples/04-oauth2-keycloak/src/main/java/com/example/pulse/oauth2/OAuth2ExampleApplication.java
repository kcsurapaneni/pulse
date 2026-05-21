package com.example.pulse.oauth2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Regular {@code @SpringBootApplication} entry point — knows nothing about Testcontainers.
 * The Keycloak container is wired in via {@code TestOAuth2ExampleApplication} (under
 * {@code src/test/java}) using Spring Boot's {@code SpringApplication.from(...).with(...)}
 * pattern, so the production classpath of this example stays clean.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@SpringBootApplication
public class OAuth2ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(OAuth2ExampleApplication.class, args);
    }
}
