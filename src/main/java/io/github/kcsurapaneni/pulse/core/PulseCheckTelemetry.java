package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Shared telemetry for blocking and reactive adapters. Tracks last-success / last-failure /
 * last-status state, decorates each {@link Health} result with {@code latencyMs} /
 * {@code lastSuccessAt} / {@code lastFailureAt}, starts a {@code pulse.check} Micrometer
 * Observation around every check (low-cardinality tags {@code name} and {@code kind}), and
 * logs at WARN on UP→non-UP transitions / INFO on DOWN→UP recoveries.
 *
 * <p>The Observation is the integration point for metrics + traces — consumers configure
 * exporters (OTLP, Prometheus, Datadog, etc.) at the application level via standard Spring
 * Boot wiring; Pulse never couples to a specific exporter. When no exporter is configured
 * the underlying registry is {@link ObservationRegistry#NOOP} and the call is effectively
 * free.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public final class PulseCheckTelemetry {

    private static final Logger log = LoggerFactory.getLogger(PulseCheckTelemetry.class);

    /** Observation name. Consumers see this as the metric/span name. */
    public static final String OBSERVATION_NAME = "pulse.check";

    private final String name;
    private final String kind;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;

    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();
    private final AtomicReference<Status> lastStatus = new AtomicReference<>();

    public PulseCheckTelemetry(String name, String kind, Clock clock,
            ObservationRegistry observationRegistry) {
        this.name = name;
        this.kind = kind;
        this.clock = clock;
        this.observationRegistry = observationRegistry != null
                ? observationRegistry
                : ObservationRegistry.NOOP;
    }

    /**
     * Starts a {@code pulse.check} Observation tagged with {@code name} + {@code kind}. The
     * caller is responsible for stopping the observation (try/finally or
     * {@link reactor.core.publisher.Mono#doFinally(java.util.function.Consumer)}).
     */
    public Observation startObservation() {
        return Observation.start(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("name", name)
                .lowCardinalityKeyValue("kind", kind);
    }

    /**
     * Records the outcome of a completed check. Updates last-success/last-failure timestamps
     * and emits a transition log when the status differs from the previous probe's status.
     * The first call after startup never logs (no previous state to compare against).
     */
    public void recordOutcome(Health result, Instant now) {
        Status status = result.getStatus();
        if (Status.UP.equals(status)) {
            lastSuccess.set(now);
        }
        else {
            lastFailure.set(now);
        }
        Status previous = lastStatus.getAndSet(status);
        if (previous != null && !previous.equals(status)) {
            if (Status.UP.equals(status)) {
                log.info("Pulse check '{}' (kind={}) recovered to UP", name, kind);
            }
            else {
                log.warn("Pulse check '{}' (kind={}) flipped to {} — details: {}",
                        name, kind, status, result.getDetails());
            }
        }
    }

    /**
     * Records an exception thrown from {@code check()} as a DOWN outcome. The exception class
     * + message replaces the details map in the transition log, since the adapter hasn't yet
     * built a full Health result.
     */
    public void recordException(Throwable error, Instant now) {
        lastFailure.set(now);
        Status previous = lastStatus.getAndSet(Status.DOWN);
        if (previous != null && !Status.DOWN.equals(previous)) {
            log.warn("Pulse check '{}' (kind={}) flipped to DOWN — error: {}: {}",
                    name, kind, error.getClass().getSimpleName(), error.getMessage());
        }
    }

    /**
     * Adds {@code latencyMs} / {@code lastSuccessAt} / {@code lastFailureAt} to the builder.
     */
    public Health.Builder decorate(Health.Builder b, Instant start, Instant now) {
        b.withDetail("latencyMs", Duration.between(start, now).toMillis());
        Instant ok = lastSuccess.get();
        if (ok != null) {
            b.withDetail("lastSuccessAt", ok.toString());
        }
        Instant fail = lastFailure.get();
        if (fail != null) {
            b.withDetail("lastFailureAt", fail.toString());
        }
        return b;
    }

    public Clock clock() {
        return clock;
    }

    public String name() {
        return name;
    }

    public String kind() {
        return kind;
    }
}
