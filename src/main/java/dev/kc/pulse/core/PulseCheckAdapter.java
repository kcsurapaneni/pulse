package dev.kc.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Bridges {@link PulseCheck} SPI instances into Spring Boot's
 * {@link AbstractHealthIndicator}. Decorates every result with {@code latencyMs} /
 * {@code lastSuccessAt} / {@code lastFailureAt}, and enforces an outer deadline so a
 * hung {@code check()} cannot block the {@code /actuator/health} response.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class PulseCheckAdapter extends AbstractHealthIndicator {

    private final PulseCheck check;
    private final Clock clock;
    private final Duration timeout;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();

    public PulseCheckAdapter(PulseCheck check, Clock clock, Duration timeout) {
        super("Health check '" + check.name() + "' failed");
        this.check = check;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Instant start = clock.instant();
        try {
            Health result = runWithTimeout();
            builder.status(result.getStatus()).withDetails(result.getDetails());
            if (Status.UP.equals(result.getStatus())) {
                lastSuccess.set(clock.instant());
            }
            else {
                lastFailure.set(clock.instant());
            }
            decorate(builder, start);
        }
        catch (TimeoutException ex) {
            lastFailure.set(clock.instant());
            builder.down()
                    .withDetail("error", "check timed out after " + timeout)
                    .withDetail("timeout", timeout.toString());
            decorate(builder, start);
        }
        catch (Exception ex) {
            lastFailure.set(clock.instant());
            decorate(builder, start);
            throw ex;
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

    private void decorate(Health.Builder builder, Instant start) {
        builder.withDetail("latencyMs", Duration.between(start, clock.instant()).toMillis());
        Instant ok = lastSuccess.get();
        if (ok != null) {
            builder.withDetail("lastSuccessAt", ok.toString());
        }
        Instant fail = lastFailure.get();
        if (fail != null) {
            builder.withDetail("lastFailureAt", fail.toString());
        }
    }
}
