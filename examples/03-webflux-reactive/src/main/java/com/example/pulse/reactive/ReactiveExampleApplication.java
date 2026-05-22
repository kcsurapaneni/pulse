package com.example.pulse.reactive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@SpringBootApplication
public class ReactiveExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReactiveExampleApplication.class, args);
    }

    @Bean
    WebClient downstreamClient(WebClient.Builder builder) {
        // In a real deployment, point this at the actual service you want to verify. Here it
        // targets the example app's own MockStatusController so the demo runs without any
        // external network dependency.
        return builder.baseUrl("http://localhost:8080").build();
    }
}
