package io.github.kcsurapaneni.pulse.mule;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.kcsurapaneni.pulse.core.PulseAutoConfiguration;
import io.github.kcsurapaneni.pulse.core.PulseCheckAdapter;
import io.github.kcsurapaneni.pulse.core.PulseProperties;
import io.github.kcsurapaneni.pulse.mule.MuleProperties.Service;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
@ConditionalOnProperty(prefix = "pulse.mule", name = "enabled", havingValue = "true")
@ConditionalOnEnabledHealthIndicator("mule")
@EnableConfigurationProperties(MuleProperties.class)
public class MuleAutoConfiguration {

    @Bean(name = "muleHealthHttpClient")
    @ConditionalOnMissingBean(name = "muleHealthHttpClient")
    public HttpClient muleHealthHttpClient(MuleProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.getTimeout().dividedBy(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean(name = "mule")
    @ConditionalOnMissingBean(name = "mule")
    public CompositeHealthContributor mule(
            MuleProperties props,
            Clock pulseClock,
            PulseProperties pulseProperties,
            @Qualifier("muleHealthHttpClient") HttpClient muleHealthHttpClient,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry observationRegistry = observationRegistryProvider
                .getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (Service svc : props.getServices()) {
            if (map.containsKey(svc.getName())) {
                throw new IllegalStateException("Duplicate mule service name: " + svc.getName());
            }
            map.put(svc.getName(), new PulseCheckAdapter(
                    new MuleCheck(svc, muleHealthHttpClient, props.getTimeout()), pulseClock,
                    pulseProperties.getCheckTimeout(), "mule", observationRegistry));
        }
        return CompositeHealthContributor.fromMap(map);
    }
}
