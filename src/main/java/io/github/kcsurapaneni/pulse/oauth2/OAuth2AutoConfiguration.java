package io.github.kcsurapaneni.pulse.oauth2;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.kcsurapaneni.pulse.core.PulseAutoConfiguration;
import io.github.kcsurapaneni.pulse.core.PulseCheckAdapter;
import io.github.kcsurapaneni.pulse.core.PulseProperties;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.Provider;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass({ HealthIndicator.class, ObjectMapper.class, ClientRegistrationRepository.class })
@ConditionalOnProperty(prefix = "pulse.oauth2", name = "enabled", havingValue = "true")
@ConditionalOnEnabledHealthIndicator("oauth2")
@EnableConfigurationProperties(OAuth2Properties.class)
public class OAuth2AutoConfiguration {

    @Bean(name = "oauth2HealthHttpClient")
    @ConditionalOnMissingBean(name = "oauth2HealthHttpClient")
    public HttpClient oauth2HealthHttpClient(OAuth2Properties props) {
        // connect timeout = half the total budget so TCP/TLS can't consume the entire deadline
        // before the per-request timeout has any room to apply.
        return HttpClient.newBuilder()
                .connectTimeout(props.getTimeout().dividedBy(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean(name = "oauth2")
    @ConditionalOnBean(ClientRegistrationRepository.class)
    @ConditionalOnMissingBean(name = "oauth2")
    public CompositeHealthContributor oauth2(
            OAuth2Properties props,
            Clock pulseClock,
            PulseProperties pulseProperties,
            ClientRegistrationRepository clientRegistrationRepository,
            @Qualifier("oauth2HealthHttpClient") HttpClient oauth2HealthHttpClient,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Qualifier(PulseAutoConfiguration.HEALTH_EXECUTOR_BEAN_NAME) Executor pulseHealthExecutor) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ObservationRegistry observationRegistry = observationRegistryProvider
                .getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (Provider provider : props.getProviders()) {
            if (map.containsKey(provider.getName())) {
                throw new IllegalStateException("Duplicate oauth2 provider name: " + provider.getName());
            }
            OAuth2Check check = new OAuth2Check(provider, clientRegistrationRepository,
                    oauth2HealthHttpClient, props.getTimeout(), pulseClock, objectMapper);
            map.put(provider.getName(),
                    new PulseCheckAdapter(check, pulseClock, pulseProperties.getCheckTimeout(),
                            "oauth2", observationRegistry, pulseHealthExecutor));
        }
        return CompositeHealthContributor.fromMap(map);
    }
}
