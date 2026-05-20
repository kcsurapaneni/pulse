package dev.kc.pulse.mount;

import java.nio.file.Path;

import dev.kc.pulse.core.PulseAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class MountPointAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PulseAutoConfiguration.class,
                    MountPointAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean("mount"));
    }

    @Test
    void enabledWhenPropertySet(@TempDir Path tmp) {
        runner.withPropertyValues(
                "pulse.mount.enabled=true",
                "pulse.mount.points[0].name=tmp",
                "pulse.mount.points[0].path=" + tmp)
                .run(ctx -> {
                    assertThat(ctx).hasBean("mount");
                    CompositeHealthContributor mount = ctx.getBean("mount", CompositeHealthContributor.class);
                    assertThat(mount.stream().map(CompositeHealthContributor.Entry::name))
                            .containsExactly("tmp");
                });
    }

    @Test
    void springsManagementHealthDisablesTheContributor(@TempDir Path tmp) {
        // Spring's standard kill-switch (management.health.<name>.enabled=false) should
        // win even if our own pulse.mount.enabled is true.
        runner.withPropertyValues(
                "pulse.mount.enabled=true",
                "pulse.mount.points[0].name=tmp",
                "pulse.mount.points[0].path=" + tmp,
                "management.health.mount.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("mount"));
    }

    @Test
    void failsFastOnBlankName(@TempDir Path tmp) {
        runner.withPropertyValues(
                "pulse.mount.enabled=true",
                "pulse.mount.points[0].name=",
                "pulse.mount.points[0].path=" + tmp)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
