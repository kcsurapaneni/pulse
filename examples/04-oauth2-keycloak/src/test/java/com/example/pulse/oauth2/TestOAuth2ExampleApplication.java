package com.example.pulse.oauth2;

import org.springframework.boot.SpringApplication;

/**
 * Test-time entry point that wires {@link TestcontainersConfiguration} into the production
 * {@link OAuth2ExampleApplication}. Run with {@code mvn spring-boot:test-run}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class TestOAuth2ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.from(OAuth2ExampleApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
