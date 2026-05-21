package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PulseAutoConfiguration.class));

    @Test
    void registersClockBean() {
        runner.run(ctx -> assertThat(ctx).hasBean("pulseClock"));
    }

    @Test
    void emptyCustomCompositeWhenNoSpiBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("pulseCustom");
            CompositeHealthContributor c = ctx.getBean("pulseCustom", CompositeHealthContributor.class);
            assertThat(c.stream()).isEmpty();
        });
    }

    @Test
    void discoversSpiBeans() {
        runner.withUserConfiguration(WithCheckBean.class).run(ctx -> {
            CompositeHealthContributor c = ctx.getBean("pulseCustom", CompositeHealthContributor.class);
            assertThat(c.stream().map(CompositeHealthContributor.Entry::name))
                    .containsExactly("my-check");
        });
    }

    @Test
    void respectsUserProvidedClock() {
        Clock fixed = Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        runner.withBean("pulseClock", Clock.class, () -> fixed).run(ctx -> {
            assertThat(ctx.getBean("pulseClock", Clock.class)).isSameAs(fixed);
        });
    }

    @Test
    void defaultsCheckTimeoutToFiveSeconds() {
        runner.run(ctx -> {
            PulseProperties props = ctx.getBean(PulseProperties.class);
            assertThat(props.getCheckTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void springsManagementHealthDisablesPulseCustom() {
        // Spring's standard kill-switch on the pulseCustom composite — even when SPI
        // beans are present, setting management.health.pulseCustom.enabled=false should
        // suppress the composite contributor entirely.
        runner.withUserConfiguration(WithCheckBean.class)
                .withPropertyValues("management.health.pulseCustom.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("pulseCustom"));
    }

    @Test
    void respectsCustomCheckTimeout() {
        runner.withPropertyValues("pulse.check-timeout=10s")
                .run(ctx -> {
                    PulseProperties props = ctx.getBean(PulseProperties.class);
                    assertThat(props.getCheckTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Configuration
    static class WithCheckBean {

        @Bean
        PulseCheck myCheck() {
            return new PulseCheck() {
                @Override
                public String name() {
                    return "my-check";
                }

                @Override
                public Health check() {
                    return Health.up().build();
                }
            };
        }
    }
}
