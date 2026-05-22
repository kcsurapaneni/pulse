package com.example.pulse.reactive;

import io.github.kcsurapaneni.pulse.core.ReactivePulseCheck;

import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * Pings the example app's own {@code MockStatusController} via {@link WebClient}, fully on the
 * reactive scheduler. Returns a {@link Mono} that the {@code pulseReactive} composite consumes
 * without ever blocking a thread.
 *
 * <p>In a real deployment you'd point the {@code downstreamClient} bean at whatever service
 * you actually want to verify (a payments API, a feature-flag service, etc.). Hitting an
 * in-process mock here keeps the example self-contained — no external network needed.
 *
 * <p>Surfaces under {@code /actuator/health/pulseReactive/downstream}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@Component
class DownstreamReactiveCheck implements ReactivePulseCheck {

    private final WebClient downstreamClient;

    DownstreamReactiveCheck(WebClient downstreamClient) {
        this.downstreamClient = downstreamClient;
    }

    @Override
    public String name() {
        return "downstream";
    }

    @Override
    public Mono<Health> check() {
        return downstreamClient.get()
                .uri("/mock/status/200")
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
