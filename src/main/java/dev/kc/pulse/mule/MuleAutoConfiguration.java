package dev.kc.pulse.mule;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.kc.pulse.core.PulseAutoConfiguration;
import dev.kc.pulse.core.PulseCheckAdapter;
import dev.kc.pulse.core.PulseNames;
import dev.kc.pulse.mule.MuleProperties.Service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
            @Qualifier("muleHealthHttpClient") HttpClient muleHealthHttpClient) {
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (Service svc : props.getServices()) {
            validate(svc);
            if (map.containsKey(svc.getName())) {
                throw new IllegalStateException("Duplicate mule service name: " + svc.getName());
            }
            map.put(svc.getName(), new PulseCheckAdapter(
                    new MuleCheck(svc, muleHealthHttpClient, props.getTimeout()), pulseClock));
        }
        return CompositeHealthContributor.fromMap(map);
    }

    private static void validate(Service svc) {
        PulseNames.validate(svc.getName(), "Mule service");
        if (svc.getUrl() == null || svc.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "pulse.mule.services[name=" + svc.getName() + "].url must not be blank");
        }
    }
}
