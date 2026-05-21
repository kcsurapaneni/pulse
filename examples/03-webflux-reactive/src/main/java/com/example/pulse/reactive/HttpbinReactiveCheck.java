package com.example.pulse.reactive;

import io.github.kcsurapaneni.pulse.core.ReactivePulseCheck;

import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * Pings {@code httpbin.org/status/200} on the reactive scheduler via {@link WebClient}.
 * Returns a {@link Mono} that the {@code pulseReactive} composite consumes without ever
 * blocking a thread.
 *
 * <p>Surfaces under {@code /actuator/health/pulseReactive/httpbin}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@Component
class HttpbinReactiveCheck implements ReactivePulseCheck {

    private final WebClient httpbinClient;

    HttpbinReactiveCheck(WebClient httpbinClient) {
        this.httpbinClient = httpbinClient;
    }

    @Override
    public String name() {
        return "httpbin";
    }

    @Override
    public Mono<Health> check() {
        return httpbinClient.get()
                .uri("/status/200")
                .retrieve()
                .toBodilessEntity()
                .map(response -> Health.up()
                        .withDetail("httpStatus", response.getStatusCode().value())
                        .build())
                .onErrorResume(ex -> Mono.just(Health.down()
                        .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                        .build()));
    }
}
