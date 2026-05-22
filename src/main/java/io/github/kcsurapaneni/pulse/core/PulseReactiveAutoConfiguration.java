package io.github.kcsurapaneni.pulse.core;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;

import reactor.core.publisher.Mono;

/**
 * Registers the {@code pulseReactive} composite contributor that aggregates all
 * {@link ReactivePulseCheck} SPI beans. Activates only when {@code reactor-core} is on the
 * classpath, so apps that don't use WebFlux pay nothing.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass({ Mono.class, ReactiveHealthIndicator.class })
public class PulseReactiveAutoConfiguration {

    @Bean(name = "pulseReactive")
    @ConditionalOnMissingBean(name = "pulseReactive")
    @ConditionalOnEnabledHealthIndicator("pulseReactive")
    public CompositeReactiveHealthContributor pulseReactive(
            ObjectProvider<ReactivePulseCheck> checks, Clock pulseClock,
            PulseProperties pulseProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry observationRegistry = observationRegistryProvider
                .getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, ReactiveHealthContributor> map = new LinkedHashMap<>();
        checks.orderedStream().forEach(check -> {
            String name = check.name();
            try {
                PulseNames.validate(name, "ReactivePulseCheck");
            }
            catch (IllegalStateException ex) {
                throw new IllegalStateException("ReactivePulseCheck bean "
                        + check.getClass().getName() + ": " + ex.getMessage(), ex);
            }
            ReactiveHealthContributor existing = map.put(name,
                    new ReactivePulseCheckAdapter(check, pulseClock,
                            pulseProperties.getCheckTimeout(), "reactive", observationRegistry));
            if (existing != null) {
                throw new IllegalStateException("Duplicate ReactivePulseCheck name: " + name);
            }
        });
        return CompositeReactiveHealthContributor.fromMap(map);
    }
}
