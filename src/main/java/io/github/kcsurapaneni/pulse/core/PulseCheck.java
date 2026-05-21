package io.github.kcsurapaneni.pulse.core;

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
}
