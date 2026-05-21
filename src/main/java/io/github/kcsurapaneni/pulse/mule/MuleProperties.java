package io.github.kcsurapaneni.pulse.mule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.mule")
public class MuleProperties {

    /**
     * Master switch for the Mule HTTP check. Defaults to {@code false} so the check only
     * registers when explicitly enabled.
     */
    private boolean enabled;

    /**
     * Per-request deadline applied to each Mule service ping. Also used to size the connect
     * timeout (half this value).
     */
    private Duration timeout = Duration.ofSeconds(2);

    /**
     * K8s probe groups the {@code mule} composite participates in. Default {@code [readiness]} —
     * downstream HTTP outage drops the pod from the LB but shouldn't restart it.
     */
    private List<String> probes = new ArrayList<>(List.of("readiness"));

    /**
     * Mule services to ping. Each entry becomes a sub-contributor under
     * {@code /actuator/health/mule/<name>}.
     */
    private List<Service> services = new ArrayList<>();

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

    public List<Service> getServices() {
        return services;
    }

    public void setServices(List<Service> services) {
        this.services = services;
    }

    /**
     * Configuration for a single Mule (or any HTTP) service to ping.
     */
    public static class Service {

        /**
         * Component key under {@code mule.<name>} in {@code /actuator/health}. Must be non-blank
         * and must not contain {@code '/'}.
         */
        private String name;

        /**
         * URL to GET. Must use the {@code http} or {@code https} scheme.
         */
        private String url;

        /**
         * HTTP status code that counts as healthy. Defaults to {@code 200}.
         */
        private int expectedStatus = 200;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getExpectedStatus() {
            return expectedStatus;
        }

        public void setExpectedStatus(int expectedStatus) {
            this.expectedStatus = expectedStatus;
        }
    }
}
