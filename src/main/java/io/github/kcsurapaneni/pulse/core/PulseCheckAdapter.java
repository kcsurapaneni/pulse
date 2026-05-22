package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Bridges {@link PulseCheck} SPI instances into Spring Boot's
 * {@link AbstractHealthIndicator}. Decorates every result with {@code latencyMs} /
 * {@code lastSuccessAt} / {@code lastFailureAt}, and enforces an outer deadline so a
 * hung {@code check()} cannot block the {@code /actuator/health} response. Records a
 * {@code pulse.check} Micrometer Observation around each invocation; see
 * {@link PulseCheckTelemetry}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class PulseCheckAdapter extends AbstractHealthIndicator {

    private static final String DEFAULT_KIND = "custom";

    private final PulseCheck check;
    private final Duration timeout;
    private final PulseCheckTelemetry telemetry;

    /**
     * Convenience constructor that tags the check's kind as {@code "custom"} and uses a NOOP
     * {@link ObservationRegistry}. Intended for tests + backwards source compatibility; production
     * call sites should use the full constructor.
     */
    public PulseCheckAdapter(PulseCheck check, Clock clock, Duration timeout) {
        this(check, clock, timeout, DEFAULT_KIND, ObservationRegistry.NOOP);
    }

    public PulseCheckAdapter(PulseCheck check, Clock clock, Duration timeout, String kind,
            ObservationRegistry observationRegistry) {
        super("Health check '" + check.name() + "' failed");
        this.check = check;
        // Per-check override (PulseCheck#checkTimeout) wins over the passed-in global timeout
        // when non-null. Sampled once at construction since the SPI default is constant per-bean.
        Duration override = check.checkTimeout();
        this.timeout = override != null ? override : timeout;
        this.telemetry = new PulseCheckTelemetry(check.name(), kind, clock, observationRegistry);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Instant start = telemetry.clock().instant();
        Observation observation = telemetry.startObservation();
        try {
            try {
                Health result = runWithTimeout();
                Instant now = telemetry.clock().instant();
                builder.status(result.getStatus()).withDetails(result.getDetails());
                telemetry.recordOutcome(result, now);
                telemetry.decorate(builder, start, now);
            }
            catch (TimeoutException ex) {
                Instant now = telemetry.clock().instant();
                Health timeoutResult = new Health.Builder().down()
                        .withDetail("error", "check timed out after " + timeout)
                        .withDetail("timeout", timeout.toString())
                        .build();
                builder.status(timeoutResult.getStatus()).withDetails(timeoutResult.getDetails());
                telemetry.recordOutcome(timeoutResult, now);
                telemetry.decorate(builder, start, now);
                observation.error(ex);
            }
            catch (Exception ex) {
                Instant now = telemetry.clock().instant();
                telemetry.recordException(ex, now);
                telemetry.decorate(builder, start, now);
                observation.error(ex);
                throw ex;
            }
        }
        finally {
            observation.stop();
        }
    }

    private Health runWithTimeout() throws Exception {
        CompletableFuture<Health> future = CompletableFuture.supplyAsync(() -> {
            try {
                return check.check();
            }
            catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (ExecutionException e) {
            // Unwrap so AbstractHealthIndicator's catch turns it into builder.down(ex).
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
        catch (TimeoutException e) {
            // Best-effort interrupt of the worker. If check() doesn't respect interrupts,
            // the worker continues but its result is discarded; the next probe creates a
            // fresh future.
            future.cancel(true);
            throw e;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw e;
        }
    }
}
