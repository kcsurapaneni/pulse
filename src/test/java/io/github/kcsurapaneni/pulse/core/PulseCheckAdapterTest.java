package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

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
                // Was 60_000ms — that leaked a ghost ForkJoinPool worker for the full minute on
                // every CI run since future.cancel(true) is best-effort and Thread.sleep doesn't
                // honour interrupts immediately on all JVMs. 2 seconds is enough to cover any
                // reasonable adapter overhead while keeping CI clean.
                Thread.sleep(2_000);
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

    @Test
    void publishesObservationTaggedWithNameAndKind() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        PulseCheck ok = new PulseCheck() {
            @Override
            public String name() {
                return "okta";
            }

            @Override
            public Health check() {
                return Health.up().build();
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(ok, Clock.systemUTC(),
                Duration.ofSeconds(5), "oauth2", registry);

        adapter.health();

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(PulseCheckTelemetry.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue("name", "okta")
                .hasLowCardinalityKeyValue("kind", "oauth2")
                .hasBeenStopped();
    }

    @Test
    void supplyAsyncUsesInjectedExecutor() throws Exception {
        // The custom executor records every Runnable it executes; the adapter must route the
        // check's CompletableFuture through it, not the implicit ForkJoinPool.commonPool().
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService inner =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "pulse-test"));
        java.util.concurrent.Executor counting = task -> {
            submissions.incrementAndGet();
            inner.execute(task);
        };
        try {
            PulseCheck ok = new PulseCheck() {
                @Override
                public String name() {
                    return "executor-routed";
                }

                @Override
                public Health check() {
                    return Health.up().withDetail("threadName", Thread.currentThread().getName()).build();
                }
            };
            PulseCheckAdapter adapter = new PulseCheckAdapter(ok, Clock.systemUTC(),
                    Duration.ofSeconds(5), "custom", io.micrometer.observation.ObservationRegistry.NOOP,
                    counting);

            Health result = adapter.health();

            assertThat(result.getStatus()).isEqualTo(Status.UP);
            assertThat(submissions.get())
                    .as("custom executor must receive exactly one submission per probe")
                    .isEqualTo(1);
            assertThat(result.getDetails().get("threadName"))
                    .as("check must run on the injected executor's thread, not the common pool")
                    .isEqualTo("pulse-test");
        }
        finally {
            inner.shutdownNow();
        }
    }

    @Test
    void perCheckCheckTimeoutOverridesGlobal() {
        // SPI bean asks for a 50ms timeout; the global ceiling we pass in is 5s. The check
        // sleeps long enough to exceed the per-check cap but well inside the global one — if the
        // override is honoured, the adapter reports DOWN-timeout; if not, it'd come back UP.
        PulseCheck slow = new PulseCheck() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public Health check() throws Exception {
                Thread.sleep(1_000);
                return Health.up().build();
            }

            @Override
            public java.time.Duration checkTimeout() {
                return Duration.ofMillis(50);
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(slow, Clock.systemUTC(),
                Duration.ofSeconds(5));

        long start = System.currentTimeMillis();
        Health result = adapter.health();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).as("per-check override must beat global").isLessThan(800L);
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) result.getDetails().get("error")).startsWith("check timed out after");
        assertThat((String) result.getDetails().get("timeout")).isEqualTo("PT0.05S");
    }

    @Test
    void observationRecordsErrorOnException() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        PulseCheck boom = new PulseCheck() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public Health check() {
                throw new RuntimeException("kaboom");
            }
        };
        PulseCheckAdapter adapter = new PulseCheckAdapter(boom, Clock.systemUTC(),
                Duration.ofSeconds(5), "custom", registry);

        Health result = adapter.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(PulseCheckTelemetry.OBSERVATION_NAME)
                .that()
                .hasError()
                .hasBeenStopped();
    }
}
