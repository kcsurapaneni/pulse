package io.github.kcsurapaneni.pulse.core;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.health.contributor.Health;

import reactor.core.publisher.Mono;

/**
 * Reactive analog of {@link PulseCheck}: SPI for consumer-defined health checks that participate
 * fully in a non-blocking pipeline. Implementations are discovered automatically as Spring beans
 * and surface under {@code /actuator/health} as {@code pulseReactive.<name>}.
 *
 * <p>Auto-detected by the presence of {@code reactor-core} on the classpath; the
 * {@code pulseReactive} composite is registered only when {@link Mono} is loadable, so apps that
 * don't use WebFlux pay nothing.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>{@link #name()} must return a stable, non-blank value for the lifetime of the bean — it's
 *       sampled once at bean-creation time to register the contributor under
 *       {@code /actuator/health}.
 *   <li>{@link #check()} must be safe to subscribe to concurrently. Spring Boot's reactive
 *       health endpoint can drive multiple subscriptions in parallel.
 *   <li>The returned {@code Mono} should complete with a single {@link Health} value. Errors
 *       propagated via {@code Mono.error(...)} are caught by the adapter and surfaced as
 *       {@code DOWN} with the exception captured in {@code details.error}.
 * </ul>
 *
 * @author Krishna Chaitanya Surapaneni
 */
public interface ReactivePulseCheck {

    String name();

    Mono<Health> check();

    /**
     * Per-check outer deadline override. Return non-null to apply a different
     * {@link Mono#timeout(Duration)} than the global {@code pulse.check-timeout}. The adapter
     * samples this once at construction.
     *
     * <p>Default {@code null}: inherit the global timeout. Mirrors {@link PulseCheck#checkTimeout()}.
     */
    default Duration checkTimeout() {
        return null;
    }

    /**
     * Per-check Kubernetes probe-group routing. Return a non-empty set to <strong>override</strong>
     * (not augment) the module-level {@code pulse.reactive.probes} for this specific bean: the
     * contributor will appear in exactly the named probe groups regardless of the module-level
     * setting. Mirrors {@link PulseCheck#probes()}.
     *
     * <p>Default empty set: inherit {@code pulse.reactive.probes}.
     */
    default Set<String> probes() {
        return Set.of();
    }
}
