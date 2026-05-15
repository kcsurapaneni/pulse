package dev.kc.pulse.mule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.mule")
public class MuleProperties {

    private boolean enabled;
    private Duration timeout = Duration.ofSeconds(2);
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

    public List<Service> getServices() {
        return services;
    }

    public void setServices(List<Service> services) {
        this.services = services;
    }

    public static class Service {

        private String name;
        private String url;
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
