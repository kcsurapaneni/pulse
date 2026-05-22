package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import reactor.core.publisher.Mono;

/**
 * Bridges {@link ReactivePulseCheck} SPI instances into Spring Boot's
 * {@link AbstractReactiveHealthIndicator}. Mirrors {@link PulseCheckAdapter}'s decoration
 * (latency / last-success / last-failure timestamps) and outer-deadline semantics, but applies
 * them via Reactor operators so the pipeline never blocks. Records a {@code pulse.check}
 * Micrometer Observation around each invocation; see {@link PulseCheckTelemetry}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class ReactivePulseCheckAdapter extends AbstractReactiveHealthIndicator {

    private static final String DEFAULT_KIND = "reactive";

    private final ReactivePulseCheck check;
    private final Duration timeout;
    private final PulseCheckTelemetry telemetry;

    /**
     * Convenience constructor that tags the check's kind as {@code "reactive"} and uses a NOOP
     * {@link ObservationRegistry}. Intended for tests + backwards source compatibility; production
     * call sites should use the full constructor.
     */
    public ReactivePulseCheckAdapter(ReactivePulseCheck check, Clock clock, Duration timeout) {
        this(check, clock, timeout, DEFAULT_KIND, ObservationRegistry.NOOP);
    }

    public ReactivePulseCheckAdapter(ReactivePulseCheck check, Clock clock, Duration timeout,
            String kind, ObservationRegistry observationRegistry) {
        super("Reactive health check '" + check.name() + "' failed");
        this.check = check;
        this.timeout = timeout;
        this.telemetry = new PulseCheckTelemetry(check.name(), kind, clock, observationRegistry);
    }

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Mono.defer(() -> {
            Instant start = telemetry.clock().instant();
            Observation observation = telemetry.startObservation();
            return check.check()
                    .timeout(timeout)
                    .map(result -> buildResult(result, start))
                    .onErrorResume(TimeoutException.class,
                            ex -> Mono.fromCallable(() -> buildTimeout(start)).doOnNext(h -> observation.error(ex)))
                    .onErrorResume(ex -> Mono.fromCallable(() -> buildException(ex, start)).doOnNext(h -> observation.error(ex)))
                    .doFinally(signal -> observation.stop());
        });
    }

    private Health buildResult(Health source, Instant start) {
        Instant now = telemetry.clock().instant();
        telemetry.recordOutcome(source, now);
        return telemetry.decorate(
                new Health.Builder().status(source.getStatus()).withDetails(source.getDetails()),
                start, now).build();
    }

    private Health buildTimeout(Instant start) {
        Instant now = telemetry.clock().instant();
        Health.Builder b = new Health.Builder().down()
                .withDetail("error", "check timed out after " + timeout)
                .withDetail("timeout", timeout.toString());
        Health snapshot = b.build();
        telemetry.recordOutcome(snapshot, now);
        return telemetry.decorate(b, start, now).build();
    }

    private Health buildException(Throwable ex, Instant start) {
        Instant now = telemetry.clock().instant();
        Health.Builder b = new Health.Builder().down()
                .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        Health snapshot = b.build();
        telemetry.recordOutcome(snapshot, now);
        return telemetry.decorate(b, start, now).build();
    }
}
