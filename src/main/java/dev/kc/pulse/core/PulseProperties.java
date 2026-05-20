package dev.kc.pulse.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    private final Custom custom = new Custom();

    private final Reactive reactive = new Reactive();

    public Duration getCheckTimeout() {
        return checkTimeout;
    }

    public void setCheckTimeout(Duration checkTimeout) {
        this.checkTimeout = checkTimeout;
    }

    public Custom getCustom() {
        return custom;
    }

    public Reactive getReactive() {
        return reactive;
    }

    /**
     * Configuration for the {@code pulseCustom} composite that aggregates all
     * {@link PulseCheck} SPI beans.
     */
    public static class Custom {

        /**
         * K8s probe groups the {@code pulseCustom} composite participates in. Default
         * {@code [readiness]} — downstream failures from consumer-defined checks should
         * drop the pod from the load balancer but shouldn't trigger a restart. Set to an
         * empty list to keep {@code pulseCustom} out of the availability probe groups
         * entirely.
         */
        private List<String> probes = new ArrayList<>(List.of("readiness"));

        public List<String> getProbes() {
            return probes;
        }

        public void setProbes(List<String> probes) {
            this.probes = probes;
        }
    }

    /**
     * Configuration for the {@code pulseReactive} composite that aggregates all
     * {@link ReactivePulseCheck} SPI beans. Only registers when {@code reactor-core} is on the
     * consumer's classpath.
     */
    public static class Reactive {

        /**
         * K8s probe groups the {@code pulseReactive} composite participates in. Default
         * {@code [readiness]} — same rationale as {@code pulse.custom.probes}. Independent
         * from {@code pulse.custom.probes} so blocking and reactive custom checks can be
         * routed to different probe groups when that matters.
         */
        private List<String> probes = new ArrayList<>(List.of("readiness"));

        public List<String> getProbes() {
            return probes;
        }

        public void setProbes(List<String> probes) {
            this.probes = probes;
        }
    }
}
