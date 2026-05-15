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

    private boolean enabled;
    private Duration timeout = Duration.ofSeconds(3);

    /**
     * K8s probe groups the {@code oauth2} composite participates in. Default {@code [readiness]} —
     * IdP outage drops the pod from the LB but shouldn't restart it.
     */
    private List<String> probes = new ArrayList<>(List.of("readiness"));

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

    public static class Provider {

        private String name;
        private String registrationId;
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
