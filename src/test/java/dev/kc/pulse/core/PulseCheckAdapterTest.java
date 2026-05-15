package dev.kc.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseCheckAdapterTest {

    @Test
    void passesThroughUpResult() {
        PulseCheck ok = new PulseCheck() {
            @Override
            public String name() {
                return "ok";
            }

            @Override
            public Health check() {
                return Health.up().withDetail("foo", "bar").build();
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(ok, Clock.systemUTC(), Duration.ofSeconds(5));

        Health result = adapter.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails())
                .containsEntry("foo", "bar")
                .containsKey("latencyMs")
                .containsKey("lastSuccessAt");
    }

    @Test
    void passesThroughDownResult() {
        PulseCheck failing = new PulseCheck() {
            @Override
            public String name() {
                return "no";
            }

            @Override
            public Health check() {
                return Health.down().withDetail("why", "nope").build();
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(failing, Clock.systemUTC(),
                Duration.ofSeconds(5));

        Health result = adapter.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails())
                .containsEntry("why", "nope")
                .containsKey("latencyMs")
                .containsKey("lastFailureAt");
    }

    @Test
    void capturesCheckExceptionAsDown() {
        PulseCheck throwing = new PulseCheck() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public Health check() {
                throw new RuntimeException("kaboom");
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(throwing, Clock.systemUTC(),
                Duration.ofSeconds(5));

        Health result = adapter.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails())
                .containsKey("error")
                .containsKey("latencyMs")
                .containsKey("lastFailureAt");
    }

    @Test
    void reportsDownWhenCheckExceedsTimeout() {
        PulseCheck stuck = new PulseCheck() {
            @Override
            public String name() {
                return "stuck";
            }

            @Override
            public Health check() throws Exception {
                Thread.sleep(60_000);
                return Health.up().build();
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(stuck, Clock.systemUTC(),
                Duration.ofMillis(200));

        long startMs = System.currentTimeMillis();
        Health result = adapter.health();
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(elapsedMs)
                .as("adapter must not block past the configured timeout")
                .isLessThan(2_000L);
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) result.getDetails().get("error"))
                .startsWith("check timed out after");
        assertThat(result.getDetails())
                .containsKey("timeout")
                .containsKey("latencyMs")
                .containsKey("lastFailureAt");
    }

    @Test
    void recoversFromPriorTimeoutOnNextProbe() {
        AtomicInteger calls = new AtomicInteger();
        PulseCheck slowOnce = new PulseCheck() {
            @Override
            public String name() {
                return "slow-once";
            }

            @Override
            public Health check() throws Exception {
                if (calls.incrementAndGet() == 1) {
                    Thread.sleep(5_000);
                }
                return Health.up().build();
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(slowOnce, Clock.systemUTC(),
                Duration.ofMillis(200));

        Health first = adapter.health();
        Health second = adapter.health();

        assertThat(first.getStatus()).isEqualTo(Status.DOWN);
        assertThat(second.getStatus()).isEqualTo(Status.UP);
    }
}
