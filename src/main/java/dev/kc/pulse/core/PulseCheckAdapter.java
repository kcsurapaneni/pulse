package dev.kc.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public class PulseCheckAdapter extends AbstractHealthIndicator {

    private final PulseCheck check;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();

    public PulseCheckAdapter(PulseCheck check, Clock clock) {
        super("Health check '" + check.name() + "' failed");
        this.check = check;
        this.clock = clock;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Instant start = clock.instant();
        try {
            Health result = check.check();
            builder.status(result.getStatus()).withDetails(result.getDetails());
            if (Status.UP.equals(result.getStatus())) {
                lastSuccess.set(clock.instant());
            }
            else {
                lastFailure.set(clock.instant());
            }
            decorate(builder, start);
        }
        catch (Exception ex) {
            lastFailure.set(clock.instant());
            decorate(builder, start);
            throw ex;
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
