package dev.kc.pulse.oauth2;

import dev.kc.pulse.core.PulseAutoConfiguration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class OAuth2AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PulseAutoConfiguration.class,
                    OAuth2AutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean("oauth2"));
    }

    @Test
    void enabledRegistersBeansAndClient() {
        runner.withUserConfiguration(StubRegistrationRepository.class)
                .withPropertyValues(
                        "pulse.oauth2.enabled=true",
                        "pulse.oauth2.providers[0].name=okta",
                        "pulse.oauth2.providers[0].registration-id=okta")
                .run(ctx -> {
                    assertThat(ctx).hasBean("oauth2");
                    assertThat(ctx).hasBean("oauth2HealthHttpClient");
                    CompositeHealthContributor oauth2 = ctx.getBean("oauth2", CompositeHealthContributor.class);
                    assertThat(oauth2.stream().map(CompositeHealthContributor.Entry::name))
                            .containsExactly("okta");
                });
    }

    @Test
    void failsFastWhenRegistrationIdMissing() {
        runner.withUserConfiguration(StubRegistrationRepository.class)
                .withPropertyValues(
                        "pulse.oauth2.enabled=true",
                        "pulse.oauth2.providers[0].name=okta")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void backsOffWhenNoClientRegistrationRepositoryBean() {
        runner.withPropertyValues(
                "pulse.oauth2.enabled=true",
                "pulse.oauth2.providers[0].name=okta",
                "pulse.oauth2.providers[0].registration-id=okta")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("oauth2"));
    }

    @Configuration
    static class StubRegistrationRepository {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration reg = ClientRegistration.withRegistrationId("okta")
                    .clientId("c").clientSecret("s")
                    .tokenUri("https://example/token")
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .build();
            return new InMemoryClientRegistrationRepository(reg);
        }
    }
}
