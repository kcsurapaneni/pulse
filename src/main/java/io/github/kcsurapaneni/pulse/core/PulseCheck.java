package io.github.kcsurapaneni.pulse.core;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.health.contributor.Health;

/**
 * SPI for consumer-defined health checks. Implementations are discovered automatically as Spring
 * beans and surface under {@code /actuator/health} as {@code pulseCustom.<name>}.
 *
 * <p>The {@code check()} signature intentionally declares {@code throws Exception} to mirror
 * Spring Boot's own {@link org.springframework.boot.health.contributor.AbstractHealthIndicator#doHealthCheck}
 * — it lets implementers throw any failure mode (IOException, custom domain exceptions, etc.)
 * and have the adapter translate it to a {@code DOWN} status with the exception captured.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>{@link #name()} must return a stable, non-blank value for the lifetime of the bean — it's
 *       sampled once at bean-creation time to register the contributor under {@code /actuator/health}.
 *   <li>{@link #check()} must be safe to call concurrently. Spring Boot's health endpoint can
 *       invoke contributors in parallel, and Pulse never serialises calls per-bean.
 * </ul>
 *
 * @author Krishna Chaitanya Surapaneni
 */
public interface PulseCheck {

    String name();

    @SuppressWarnings("java:S112") // intentional: matches AbstractHealthIndicator.doHealthCheck signature
    Health check() throws Exception;

    /**
     * Per-check outer deadline override. Return non-null to cap {@code check()} wall-clock time
     * at a value different from the global {@code pulse.check-timeout}. The adapter samples this
     * once at construction.
     *
     * <p>Default {@code null}: inherit the global timeout. Useful when one check legitimately
     * needs longer than the rest (a paginated downstream query, a hostile-network probe) — the
     * default lets the consumer raise it for just that bean without slackening the global cap
     * for every other check.
     */
    default Duration checkTimeout() {
        return null;
    }

    /**
     * Per-check Kubernetes probe-group routing. Return a non-empty set to <strong>override</strong>
     * (not augment) the module-level {@code pulse.custom.probes} for this specific bean: the
     * contributor will appear in exactly the named probe groups (typically {@code liveness} and/or
     * {@code readiness}) regardless of the module-level setting.
     *
     * <p>Default empty set: inherit {@code pulse.custom.probes}. Useful when one SPI check is
     * pod-fatal (liveness) and another isn't (readiness only) and you'd otherwise have to roll
     * your own composite. Override semantics keep the mental model simple — what you return is
     * exactly where the check lands.
     */
    default Set<String> probes() {
        return Set.of();
    }
}
