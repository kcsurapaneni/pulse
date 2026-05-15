package dev.kc.pulse.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Pulse properties. Per-module properties live in each module's own
 * {@code @ConfigurationProperties} class
 * (e.g. {@link dev.kc.pulse.mount.MountPointProperties}).
 *
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse")
public class PulseProperties {

    /**
     * Maximum wall-clock time any single {@link PulseCheck#check()} may take before the
     * adapter reports {@code DOWN} with {@code details.error="check timed out after …"}.
     * Prevents a hung check (degraded NFS mount, stuck socket, deadlocked custom check)
     * from blocking the entire {@code /actuator/health} response — which would otherwise
     * trip Kubernetes liveness probes and get the pod killed.
     *
     * <p>This is an <b>outer</b> deadline applied uniformly. Module-specific
     * request timeouts ({@code pulse.mule.timeout}, {@code pulse.oauth2.timeout}) still
     * apply at the inner request level; {@code check-timeout} just caps the total so a
     * missed inner timeout can't escalate into a blocked actuator response.
     */
    private Duration checkTimeout = Duration.ofSeconds(5);

    public Duration getCheckTimeout() {
        return checkTimeout;
    }

    public void setCheckTimeout(Duration checkTimeout) {
        this.checkTimeout = checkTimeout;
    }
}
