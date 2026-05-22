package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseCheckTelemetryTest {

    private static final Instant T0 = Instant.parse("2026-05-21T10:00:00Z");

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PulseCheckTelemetry.class))
                .addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PulseCheckTelemetry.class))
                .detachAppender(appender);
    }

    @Test
    void decorateAddsLatencyAndTimestamps() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("alpha", "custom",
                Clock.fixed(T0.plusMillis(42), ZoneOffset.UTC), null);

        t.recordOutcome(Health.up().build(), T0.plusMillis(42));

        Health.Builder b = new Health.Builder().up();
        t.decorate(b, T0, T0.plusMillis(42));
        Health h = b.build();

        assertThat(h.getDetails())
                .containsEntry("latencyMs", 42L)
                .containsEntry("lastSuccessAt", T0.plusMillis(42).toString());
    }

    @Test
    void firstCallDoesNotLogTransition() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("alpha", "custom",
                Clock.fixed(T0, ZoneOffset.UTC), null);

        t.recordOutcome(Health.up().build(), T0);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void upToDownLogsWarnTransition() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("payments", "custom",
                Clock.fixed(T0, ZoneOffset.UTC), null);
        t.recordOutcome(Health.up().build(), T0);
        appender.list.clear();

        t.recordOutcome(Health.down().withDetail("error", "boom").build(), T0.plusSeconds(1));

        assertThat(appender.list)
                .hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("payments")
                            .contains("kind=custom")
                            .contains("DOWN")
                            .contains("boom");
                });
    }

    @Test
    void downToUpLogsInfoTransition() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("payments", "oauth2",
                Clock.fixed(T0, ZoneOffset.UTC), null);
        t.recordOutcome(Health.down().build(), T0);
        appender.list.clear();

        t.recordOutcome(Health.up().build(), T0.plusSeconds(1));

        assertThat(appender.list)
                .hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("payments")
                            .contains("kind=oauth2")
                            .contains("recovered to UP");
                });
    }

    @Test
    void repeatedSameStatusDoesNotLog() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("alpha", "custom",
                Clock.fixed(T0, ZoneOffset.UTC), null);
        t.recordOutcome(Health.up().build(), T0);
        t.recordOutcome(Health.up().build(), T0.plusSeconds(1));
        t.recordOutcome(Health.up().build(), T0.plusSeconds(2));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void recordExceptionLogsTransitionWithErrorClass() {
        PulseCheckTelemetry t = new PulseCheckTelemetry("alpha", "mule",
                Clock.fixed(T0, ZoneOffset.UTC), null);
        t.recordOutcome(Health.up().build(), T0);
        appender.list.clear();

        t.recordException(new RuntimeException("kaboom"), T0.plusSeconds(1));

        assertThat(appender.list)
                .hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("alpha")
                            .contains("kind=mule")
                            .contains("RuntimeException")
                            .contains("kaboom");
                });
    }

    @Test
    void observationCarriesNameAndKindTags() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        PulseCheckTelemetry t = new PulseCheckTelemetry("payments", "oauth2",
                Clock.fixed(T0, ZoneOffset.UTC), registry);

        Observation observation = t.startObservation();
        observation.stop();

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(PulseCheckTelemetry.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue("name", "payments")
                .hasLowCardinalityKeyValue("kind", "oauth2")
                .hasBeenStopped();
    }

    @Test
    void nullObservationRegistryFallsBackToNoop() {
        // Caller passing null shouldn't blow up — the constructor swaps in NOOP.
        AtomicReference<Throwable> error = new AtomicReference<>();
        PulseCheckTelemetry t = new PulseCheckTelemetry("alpha", "custom",
                Clock.fixed(T0, ZoneOffset.UTC), null);
        try {
            Observation o = t.startObservation();
            o.stop();
        }
        catch (Throwable ex) {
            error.set(ex);
        }
        assertThat(error.get()).isNull();
    }
}
