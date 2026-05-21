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
    WebClient httpbinClient(WebClient.Builder builder) {
        return builder.baseUrl("https://httpbin.org").build();
    }
}
