package io.github.kcsurapaneni.pulse.oauth2;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;

import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.CheckMode;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.Provider;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@Testcontainers
class OAuth2CheckKeycloakIT {

    @Container
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer()
            .withRealmImportFile("/realm-test.json");

    @Test
    void upWithValidCredentials() {
        OAuth2Check check = newCheck(buildRegistration("test-client", "test-secret"));

        Health health = check.check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("cached", false)
                .containsEntry("tokenType", "Bearer")
                .containsKey("expiresInSec");
    }

    @Test
    void downWithInvalidClientSecret() {
        OAuth2Check check = newCheck(buildRegistration("test-client", "wrong-secret"));

        Health health = check.check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .startsWith("client_credentials handshake failed:");
        assertThat(health.getDetails()).containsEntry("httpStatus", 401);
    }

    @Test
    void downWithUnknownClient() {
        OAuth2Check check = newCheck(buildRegistration("no-such-client", "anything"));

        Health health = check.check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void secondCallIsServedFromCache() {
        OAuth2Check check = newCheck(buildRegistration("test-client", "test-secret"));

        check.check();
        Health second = check.check();

        assertThat(second.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getDetails()).containsEntry("cached", true);
    }

    @Test
    void reachableModeHitsDiscoveryDocument() {
        // Reachable mode against a real Keycloak: GET the discovery doc, expect 200. Credentials
        // are not exercised — wrong-secret + reachable should still report UP.
        Provider p = new Provider();
        p.setName("kc");
        p.setRegistrationId("kc");
        p.setMode(CheckMode.REACHABLE);
        p.setDiscoveryUri(KEYCLOAK.getAuthServerUrl() + "/realms/test/.well-known/openid-configuration");
        OAuth2Check check = new OAuth2Check(p,
                new InMemoryClientRegistrationRepository(buildRegistration("test-client", "wrong-secret")),
                HttpClient.newHttpClient(), Duration.ofSeconds(10), Clock.systemUTC(),
                new ObjectMapper());

        Health health = check.check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("mode", "reachable")
                .containsEntry("httpStatus", 200);
    }

    private OAuth2Check newCheck(ClientRegistration registration) {
        ClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(registration);
        Provider p = new Provider();
        p.setName("kc");
        p.setRegistrationId("kc");
        return new OAuth2Check(p, repo, HttpClient.newHttpClient(), Duration.ofSeconds(10),
                Clock.systemUTC(), new ObjectMapper());
    }

    private static ClientRegistration buildRegistration(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId("kc")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tokenUri(tokenUri())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();
    }

    private static String tokenUri() {
        return KEYCLOAK.getAuthServerUrl() + "/realms/test/protocol/openid-connect/token";
    }
}
