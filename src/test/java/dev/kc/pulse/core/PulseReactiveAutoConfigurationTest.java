package dev.kc.pulse.core;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeReactiveHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseReactiveAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PulseAutoConfiguration.class,
                    PulseReactiveAutoConfiguration.class));

    @Test
    void emptyReactiveCompositeWhenNoSpiBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("pulseReactive");
            CompositeReactiveHealthContributor c =
                    ctx.getBean("pulseReactive", CompositeReactiveHealthContributor.class);
            assertThat(c.stream()).isEmpty();
        });
    }

    @Test
    void discoversReactiveSpiBeans() {
        runner.withUserConfiguration(WithReactiveCheckBean.class).run(ctx -> {
            CompositeReactiveHealthContributor c =
                    ctx.getBean("pulseReactive", CompositeReactiveHealthContributor.class);
            assertThat(c.stream().map(CompositeReactiveHealthContributor.Entry::name))
                    .containsExactly("payments");
        });
    }

    @Test
    void defaultsReactiveProbesToReadiness() {
        runner.run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.getReactive().getProbes()).containsExactly("readiness");
        });
    }

    @Test
    void respectsCustomReactiveProbes() {
        runner.withPropertyValues("pulse.reactive.probes=liveness,readiness")
                .run(ctx -> {
                    PulseProperties props = ctx.getBean(PulseProperties.class);
                    assertThat(props.getReactive().getProbes())
                            .containsExactly("liveness", "readiness");
                });
    }

    @Test
    void springsManagementHealthDisablesPulseReactive() {
        runner.withUserConfiguration(WithReactiveCheckBean.class)
                .withPropertyValues("management.health.pulseReactive.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("pulseReactive"));
    }

    @Test
    void respectsCheckTimeoutForReactiveAdapter() {
        runner.withPropertyValues("pulse.check-timeout=2s")
                .run(ctx -> {
                    PulseProperties props = ctx.getBean(PulseProperties.class);
                    assertThat(props.getCheckTimeout()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Configuration
    static class WithReactiveCheckBean {

        @Bean
        ReactivePulseCheck paymentsReactiveCheck() {
            return new ReactivePulseCheck() {
                @Override
                public String name() {
                    return "payments";
                }

                @Override
                public Mono<Health> check() {
                    return Mono.just(Health.up().build());
                }
            };
        }
    }
}
