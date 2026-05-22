package io.github.kcsurapaneni.pulse.oauth2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.oauth2")
@Validated
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
    @Valid
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
        @NotBlank
        @Pattern(regexp = "[^/]+", message = "must not contain '/'")
        private String name;

        /**
         * Id of an existing {@code spring.security.oauth2.client.registration.<id>}. Credentials,
         * token URI, scope, and auth method are read from that registration on every probe so
         * runtime secret rotation flows through automatically.
         */
        @NotBlank
        private String registrationId;

        /**
         * What to verify on each probe. {@link CheckMode#HANDSHAKE} (default) performs a real
         * {@code client_credentials} handshake and proves both that the IdP is up <em>and</em>
         * that the registered credentials still work. {@link CheckMode#REACHABLE} only GETs the
         * OIDC discovery document — lighter, no credentials exercised, but a rotated secret
         * won't be caught.
         */
        private CheckMode mode = CheckMode.HANDSHAKE;

        /**
         * Explicit URL of the OIDC discovery document used by {@link CheckMode#REACHABLE}.
         * Optional — when blank, the URL is derived as
         * {@code <issuer-uri>/.well-known/openid-configuration} from the registration's
         * {@code provider.<id>.issuer-uri}. Set this only if your Spring Security registration
         * configures {@code token-uri} directly (without {@code issuer-uri}) and you still want
         * reachable mode.
         */
        private String discoveryUri;

        /**
         * Upper bound on token reuse between live handshakes. The cache refreshes at 80 % of
         * {@code min(token.expires_in, cache-ttl)}, so a typical 1-hour IdP token with the
         * default 5-minute TTL re-handshakes every ~4 minutes. Only applied in
         * {@link CheckMode#HANDSHAKE}.
         */
        private Duration cacheTtl = Duration.ofMinutes(5);

        /**
         * What to report when a refresh-time handshake fails transiently while a still-natural-life
         * cached token is on hand. {@link OnTransientFailure#DOWN} (default) preserves the
         * "is the IdP reachable right now" semantic — transient errors clear the cache and report
         * {@code DOWN}. {@link OnTransientFailure#STALE} switches the semantic to "do we hold a
         * usable token" — short outages return {@code UP} with {@code stale: true} and a
         * {@code staleReason} as long as the cached token hasn't reached its IdP-reported natural
         * expiry. Only applied in {@link CheckMode#HANDSHAKE}.
         *
         * <p>Transient failures include {@link java.io.IOException} (DNS / TLS / connect / read
         * timeout), HTTP 5xx, and HTTP 429. HTTP 4xx other than 429 (401 / 403 / 400) always
         * clears the cache and reports {@code DOWN} regardless of this setting — those almost
         * always mean credentials are wrong, not that the IdP is having a moment.
         */
        private OnTransientFailure onTransientFailure = OnTransientFailure.DOWN;

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

        public CheckMode getMode() {
            return mode;
        }

        public void setMode(CheckMode mode) {
            this.mode = mode;
        }

        public String getDiscoveryUri() {
            return discoveryUri;
        }

        public void setDiscoveryUri(String discoveryUri) {
            this.discoveryUri = discoveryUri;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public OnTransientFailure getOnTransientFailure() {
            return onTransientFailure;
        }

        public void setOnTransientFailure(OnTransientFailure onTransientFailure) {
            this.onTransientFailure = onTransientFailure;
        }
    }

    /**
     * How transient handshake failures interact with the cached token.
     */
    public enum OnTransientFailure {

        /**
         * Default. Transient failures clear the cache and report {@code DOWN} — health answers
         * "is the IdP reachable right now". Preserves Pulse 0.1–0.6 behaviour.
         */
        DOWN,

        /**
         * Transient failures return the still-naturally-valid cached token with {@code stale: true}
         * and a {@code staleReason} in details — health answers "do we hold a usable token". Once
         * the cached token reaches its IdP-reported natural expiry the check goes {@code DOWN}.
         */
        STALE
    }

    /**
     * Depth of validation performed on every probe.
     */
    public enum CheckMode {

        /**
         * GET the OIDC discovery document at
         * {@code <issuer-uri>/.well-known/openid-configuration} (or the explicit
         * {@code discovery-uri}). UP on 2xx, DOWN on 5xx / 4xx / network error. No credentials
         * are exercised — a rotated secret won't be caught here.
         */
        REACHABLE,

        /**
         * Perform a real {@code client_credentials} handshake against the token endpoint. Proves
         * IdP availability <em>and</em> credential validity. Default — preserves Pulse 0.1–0.5
         * behaviour.
         */
        HANDSHAKE
    }
}
