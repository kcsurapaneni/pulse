package io.github.kcsurapaneni.pulse.mount;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.kcsurapaneni.pulse.core.PulseAutoConfiguration;
import io.github.kcsurapaneni.pulse.core.PulseCheckAdapter;
import io.github.kcsurapaneni.pulse.core.PulseProperties;
import io.github.kcsurapaneni.pulse.mount.MountPointProperties.MountPoint;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "pulse.mount", name = "enabled", havingValue = "true")
@ConditionalOnEnabledHealthIndicator("mount")
@EnableConfigurationProperties(MountPointProperties.class)
public class MountPointAutoConfiguration {

    @Bean(name = "mount")
    @ConditionalOnMissingBean(name = "mount")
    public CompositeHealthContributor mount(MountPointProperties props, Clock pulseClock,
            PulseProperties pulseProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry observationRegistry = observationRegistryProvider
                .getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (MountPoint point : props.getPoints()) {
            if (map.containsKey(point.getName())) {
                throw new IllegalStateException("Duplicate mount point name: " + point.getName());
            }
            map.put(point.getName(), new PulseCheckAdapter(new MountPointCheck(point), pulseClock,
                    pulseProperties.getCheckTimeout(), "mount", observationRegistry));
        }
        return CompositeHealthContributor.fromMap(map);
    }
}
