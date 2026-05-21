package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class ReactivePulseCheckAdapterTest {

    @Test
    void passesThroughUpResult() {
        ReactivePulseCheck ok = new ReactivePulseCheck() {
            @Override
            public String name() {
                return "ok";
            }

            @Override
            public Mono<Health> check() {
                return Mono.just(Health.up().withDetail("foo", "bar").build());
            }
        };
        ReactivePulseCheckAdapter adapter = new ReactivePulseCheckAdapter(ok, Clock.systemUTC(),
                Duration.ofSeconds(5));

        StepVerifier.create(adapter.health())
                .assertNext(h -> {
                    assertThat(h.getStatus()).isEqualTo(Status.UP);
                    assertThat(h.getDetails())
                            .containsEntry("foo", "bar")
                            .containsKey("latencyMs")
                            .containsKey("lastSuccessAt");
                })
                .verifyComplete();
    }

    @Test
    void passesThroughDownResult() {
        ReactivePulseCheck failing = new ReactivePulseCheck() {
            @Override
            public String name() {
                return "no";
            }

            @Override
            public Mono<Health> check() {
                return Mono.just(Health.down().withDetail("why", "nope").build());
            }
        };
        ReactivePulseCheckAdapter adapter = new ReactivePulseCheckAdapter(failing, Clock.systemUTC(),
                Duration.ofSeconds(5));

        StepVerifier.create(adapter.health())
                .assertNext(h -> {
                    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(h.getDetails())
                            .containsEntry("why", "nope")
                            .containsKey("latencyMs")
                            .containsKey("lastFailureAt");
                })
                .verifyComplete();
    }

    @Test
    void capturesCheckErrorAsDown() {
        ReactivePulseCheck throwing = new ReactivePulseCheck() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public Mono<Health> check() {
                return Mono.error(new RuntimeException("kaboom"));
            }
        };
        ReactivePulseCheckAdapter adapter = new ReactivePulseCheckAdapter(throwing, Clock.systemUTC(),
                Duration.ofSeconds(5));

        StepVerifier.create(adapter.health())
                .assertNext(h -> {
                    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
                    assertThat((String) h.getDetails().get("error"))
                            .contains("RuntimeException")
                            .contains("kaboom");
                    assertThat(h.getDetails())
                            .containsKey("latencyMs")
                            .containsKey("lastFailureAt");
                })
                .verifyComplete();
    }

    @Test
    void reportsDownWhenCheckExceedsTimeout() {
        ReactivePulseCheck stuck = new ReactivePulseCheck() {
            @Override
            public String name() {
                return "stuck";
            }

            @Override
            public Mono<Health> check() {
                // Never emits — sleeps for 60s in the reactive timeline.
                return Mono.never();
            }
        };
        ReactivePulseCheckAdapter adapter = new ReactivePulseCheckAdapter(stuck, Clock.systemUTC(),
                Duration.ofMillis(200));

        StepVerifier.create(adapter.health())
                .assertNext(h -> {
                    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
                    assertThat((String) h.getDetails().get("error"))
                            .startsWith("check timed out after");
                    assertThat(h.getDetails())
                            .containsKey("timeout")
                            .containsKey("latencyMs")
                            .containsKey("lastFailureAt");
                })
                .verifyComplete();
    }

    @Test
    void recoversFromPriorTimeoutOnNextProbe() {
        AtomicInteger calls = new AtomicInteger();
        ReactivePulseCheck slowOnce = new ReactivePulseCheck() {
            @Override
            public String name() {
                return "slow-once";
            }

            @Override
            public Mono<Health> check() {
                if (calls.incrementAndGet() == 1) {
                    return Mono.never();
                }
                return Mono.just(Health.up().build());
            }
        };
        ReactivePulseCheckAdapter adapter = new ReactivePulseCheckAdapter(slowOnce, Clock.systemUTC(),
                Duration.ofMillis(200));

        StepVerifier.create(adapter.health())
                .assertNext(h -> assertThat(h.getStatus()).isEqualTo(Status.DOWN))
                .verifyComplete();

        StepVerifier.create(adapter.health())
                .assertNext(h -> assertThat(h.getStatus()).isEqualTo(Status.UP))
                .verifyComplete();
    }
}
