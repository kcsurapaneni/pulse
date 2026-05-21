package com.example.pulse.oauth2;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Boots a Keycloak Testcontainer with a pre-imported realm + client_credentials client,
 * and injects the dynamic token URI into Spring Security's OAuth2 client configuration.
 *
 * <p>Pulse's OAuth2 check then resolves the {@code example} registration through Spring
 * Security's {@code ClientRegistrationRepository} — no special wiring needed for Pulse itself.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    KeycloakContainer keycloakContainer() {
        return new KeycloakContainer().withRealmImportFile("/realm-test.json");
    }

    @Bean
    DynamicPropertyRegistrar keycloakProperties(KeycloakContainer keycloak) {
        return registry -> {
            String tokenUri = keycloak.getAuthServerUrl()
                    + "/realms/test/protocol/openid-connect/token";
            registry.add("spring.security.oauth2.client.provider.example.token-uri",
                    () -> tokenUri);
        };
    }
}
