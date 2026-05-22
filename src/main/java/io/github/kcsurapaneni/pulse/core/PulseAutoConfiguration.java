package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
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

    /** Bean name of the composite contributor holding all {@link PulseCheck} SPI beans. */
    public static final String CUSTOM_COMPOSITE_NAME = "pulseCustom";

    /** Bean name of the composite contributor holding all {@code ReactivePulseCheck} SPI beans. */
    public static final String REACTIVE_COMPOSITE_NAME = "pulseReactive";

    @Bean
    @ConditionalOnMissingBean(name = "pulseClock")
    public Clock pulseClock() {
        return Clock.systemUTC();
    }

    @Bean(name = CUSTOM_COMPOSITE_NAME)
    @ConditionalOnMissingBean(name = CUSTOM_COMPOSITE_NAME)
    @ConditionalOnEnabledHealthIndicator(CUSTOM_COMPOSITE_NAME)
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

    /**
     * Applies per-check K8s probe-group routing for any {@link PulseCheck} bean whose
     * {@link PulseCheck#probes()} returns a non-empty set. Becomes a no-op when no bean has an
     * override, so the bean is safe to register unconditionally.
     */
    @Bean(name = "pulseCustomGroupsPostProcessor")
    @ConditionalOnClass(HealthEndpointGroupsPostProcessor.class)
    @ConditionalOnMissingBean(name = "pulseCustomGroupsPostProcessor")
    public PulseSpiHealthGroupsPostProcessor pulseCustomGroupsPostProcessor(
            ObjectProvider<PulseCheck> blockingChecks) {
        Map<String, Set<String>> probes = blockingChecks.orderedStream()
                .filter(c -> c.probes() != null && !c.probes().isEmpty())
                .collect(Collectors.toMap(PulseCheck::name, c -> Set.copyOf(c.probes())));
        return new PulseSpiHealthGroupsPostProcessor(CUSTOM_COMPOSITE_NAME + ".", probes);
    }
}
