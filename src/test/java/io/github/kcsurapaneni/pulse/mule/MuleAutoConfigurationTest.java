package io.github.kcsurapaneni.pulse.mule;

import io.github.kcsurapaneni.pulse.core.PulseAutoConfiguration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class MuleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PulseAutoConfiguration.class,
                    MuleAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean("mule"));
    }

    @Test
    void enabledRegistersBeansAndClient() {
        runner.withPropertyValues(
                "pulse.mule.enabled=true",
                "pulse.mule.services[0].name=order",
                "pulse.mule.services[0].url=http://localhost/health")
                .run(ctx -> {
                    assertThat(ctx).hasBean("mule");
                    assertThat(ctx).hasBean("muleHealthHttpClient");
                    CompositeHealthContributor mule = ctx.getBean("mule", CompositeHealthContributor.class);
                    assertThat(mule.stream().map(CompositeHealthContributor.Entry::name))
                            .containsExactly("order");
                });
    }

    @Test
    void springsManagementHealthDisablesTheContributor() {
        runner.withPropertyValues(
                "pulse.mule.enabled=true",
                "pulse.mule.services[0].name=order",
                "pulse.mule.services[0].url=http://localhost/health",
                "management.health.mule.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("mule"));
    }

    @Test
    void failsFastOnBlankUrl() {
        runner.withPropertyValues(
                "pulse.mule.enabled=true",
                "pulse.mule.services[0].name=order",
                "pulse.mule.services[0].url=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failsFastOnBlankName() {
        runner.withPropertyValues(
                "pulse.mule.enabled=true",
                "pulse.mule.services[0].name=",
                "pulse.mule.services[0].url=http://localhost/health")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failsFastOnNameContainingSlash() {
        runner.withPropertyValues(
                "pulse.mule.enabled=true",
                "pulse.mule.services[0].name=foo/bar",
                "pulse.mule.services[0].url=http://localhost/health")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
