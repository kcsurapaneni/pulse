package dev.kc.pulse.oauth2;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kc.pulse.core.PulseAutoConfiguration;
import dev.kc.pulse.core.PulseCheckAdapter;
import dev.kc.pulse.core.PulseNames;
import dev.kc.pulse.oauth2.OAuth2Properties.Provider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
            ClientRegistrationRepository clientRegistrationRepository,
            @Qualifier("oauth2HealthHttpClient") HttpClient oauth2HealthHttpClient,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (Provider provider : props.getProviders()) {
            validate(provider);
            if (map.containsKey(provider.getName())) {
                throw new IllegalStateException("Duplicate oauth2 provider name: " + provider.getName());
            }
            OAuth2Check check = new OAuth2Check(provider, clientRegistrationRepository,
                    oauth2HealthHttpClient, props.getTimeout(), pulseClock, objectMapper);
            map.put(provider.getName(), new PulseCheckAdapter(check, pulseClock));
        }
        return CompositeHealthContributor.fromMap(map);
    }

    private static void validate(Provider p) {
        PulseNames.validate(p.getName(), "OAuth2 provider");
        if (p.getRegistrationId() == null || p.getRegistrationId().isBlank()) {
            throw new IllegalStateException("pulse.oauth2.providers[name=" + p.getName()
                    + "].registration-id must not be blank");
        }
    }
}
