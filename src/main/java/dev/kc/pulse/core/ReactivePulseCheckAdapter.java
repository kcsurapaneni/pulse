package dev.kc.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import reactor.core.publisher.Mono;

/**
 * Bridges {@link ReactivePulseCheck} SPI instances into Spring Boot's
 * {@link AbstractReactiveHealthIndicator}. Mirrors {@link PulseCheckAdapter}'s decoration
 * (latency / last-success / last-failure timestamps) and outer-deadline semantics, but applies
 * them via Reactor operators so the pipeline never blocks.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class ReactivePulseCheckAdapter extends AbstractReactiveHealthIndicator {

    private final ReactivePulseCheck check;
    private final Clock clock;
    private final Duration timeout;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();

    public ReactivePulseCheckAdapter(ReactivePulseCheck check, Clock clock, Duration timeout) {
        super("Reactive health check '" + check.name() + "' failed");
        this.check = check;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Mono.defer(() -> {
            Instant start = clock.instant();
            return check.check()
                    .timeout(timeout)
                    .map(result -> buildResult(result, start))
                    .onErrorResume(TimeoutException.class,
                            ex -> Mono.fromCallable(() -> buildTimeout(start)))
                    .onErrorResume(ex -> Mono.fromCallable(() -> buildException(ex, start)));
        });
    }

    private Health buildResult(Health source, Instant start) {
        Instant now = clock.instant();
        if (Status.UP.equals(source.getStatus())) {
            lastSuccess.set(now);
        }
        else {
            lastFailure.set(now);
        }
        return decorate(new Health.Builder().status(source.getStatus()).withDetails(source.getDetails()),
                start, now).build();
    }

    private Health buildTimeout(Instant start) {
        Instant now = clock.instant();
        lastFailure.set(now);
        return decorate(new Health.Builder().down()
                .withDetail("error", "check timed out after " + timeout)
                .withDetail("timeout", timeout.toString()), start, now).build();
    }

    private Health buildException(Throwable ex, Instant start) {
        Instant now = clock.instant();
        lastFailure.set(now);
        return decorate(new Health.Builder().down()
                .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage()),
                start, now).build();
    }

    private Health.Builder decorate(Health.Builder b, Instant start, Instant now) {
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
}
