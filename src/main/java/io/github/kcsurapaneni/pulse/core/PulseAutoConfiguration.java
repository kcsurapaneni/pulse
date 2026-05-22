package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@EnableConfigurationProperties(PulseProperties.class)
public class PulseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "pulseClock")
    public Clock pulseClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "pulseCustom")
    @ConditionalOnMissingBean(name = "pulseCustom")
    @ConditionalOnEnabledHealthIndicator("pulseCustom")
    public CompositeHealthContributor pulseCustom(
            ObjectProvider<PulseCheck> checks, Clock pulseClock, PulseProperties pulseProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry observationRegistry = observationRegistryProvider
                .getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        checks.orderedStream().forEach(check -> {
            String name = check.name();
            try {
                PulseNames.validate(name, "PulseCheck");
            }
            catch (IllegalStateException ex) {
                throw new IllegalStateException(
                        "PulseCheck bean " + check.getClass().getName() + ": " + ex.getMessage(), ex);
            }
            HealthContributor existing = map.put(name,
                    new PulseCheckAdapter(check, pulseClock, pulseProperties.getCheckTimeout(),
                            "custom", observationRegistry));
            if (existing != null) {
                throw new IllegalStateException("Duplicate PulseCheck name: " + name);
            }
        });
        return CompositeHealthContributor.fromMap(map);
    }
}
