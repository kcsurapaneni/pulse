package dev.kc.pulse.oauth2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.oauth2")
public class OAuth2Properties {

    /**
     * Master switch for the OAuth2 {@code client_credentials} check. Defaults to {@code false} so
     * the check only registers when explicitly enabled.
     */
    private boolean enabled;

    /**
     * Per-request deadline for token endpoint calls. Also used to size the connect timeout
     * (half this value).
     */
    private Duration timeout = Duration.ofSeconds(3);

    /**
     * K8s probe groups the {@code oauth2} composite participates in. Default {@code [readiness]} —
     * IdP outage drops the pod from the LB but shouldn't restart it.
     */
    private List<String> probes = new ArrayList<>(List.of("readiness"));

    /**
     * OAuth2 providers to health-check. Each entry references an existing
     * {@code spring.security.oauth2.client.registration.<registrationId>} so credentials don't
     * have to be duplicated into {@code pulse} config. Becomes a sub-contributor under
     * {@code /actuator/health/oauth2/<name>}.
     */
    private List<Provider> providers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public List<String> getProbes() {
        return probes;
    }

    public void setProbes(List<String> probes) {
        this.probes = probes;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers;
    }

    /**
     * Configuration for a single OAuth2 provider to health-check.
     */
    public static class Provider {

        /**
         * Component key under {@code oauth2.<name>} in {@code /actuator/health}. Must be non-blank
         * and must not contain {@code '/'}.
         */
        private String name;

        /**
         * Id of an existing {@code spring.security.oauth2.client.registration.<id>}. Credentials,
         * token URI, scope, and auth method are read from that registration on every probe so
         * runtime secret rotation flows through automatically.
         */
        private String registrationId;

        /**
         * Upper bound on token reuse between live handshakes. The cache refreshes at 80 % of
         * {@code min(token.expires_in, cache-ttl)}, so a typical 1-hour IdP token with the
         * default 5-minute TTL re-handshakes every ~4 minutes.
         */
        private Duration cacheTtl = Duration.ofMinutes(5);

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRegistrationId() {
            return registrationId;
        }

        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }
}
