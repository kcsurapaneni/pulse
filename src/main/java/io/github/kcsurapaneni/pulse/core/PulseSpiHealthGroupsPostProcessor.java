package io.github.kcsurapaneni.pulse.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * Applies per-check Kubernetes probe-group routing for SPI beans whose {@code probes()} returns a
 * non-empty set. One instance per SPI type:
 *
 * <ul>
 *   <li>{@code pulseCustom.} prefix — handled by the bean created in {@link PulseAutoConfiguration}
 *       from {@link PulseCheck#probes()}.</li>
 *   <li>{@code pulseReactive.} prefix — handled by the bean created in
 *       {@link PulseReactiveAutoConfiguration} from {@code ReactivePulseCheck#probes()}.</li>
 * </ul>
 *
 * <p>The class is parameterised by prefix so it doesn't reference {@code ReactivePulseCheck}
 * directly — which would force {@code reactor-core} onto the classpath for non-WebFlux apps.
 *
 * <p>Without this processor, every SPI check inherits its module-level probes property
 * ({@code pulse.custom.probes} or {@code pulse.reactive.probes}). With it, an individual bean's
 * {@code probes()} <strong>overrides</strong> the module-level setting for that specific bean —
 * useful when one SPI check is pod-fatal (liveness) and another isn't (readiness only).
 *
 * <p>Implementation: at startup, samples each SPI bean's {@code probes()}; for groups with at
 * least one override, wraps the corresponding {@link HealthEndpointGroup} so its
 * {@link HealthEndpointGroup#isMember(String) isMember(name)} returns the override's answer for
 * {@code <prefix><X>} names — and defers to the underlying group otherwise. Becomes a no-op
 * (returns the input {@link HealthEndpointGroups} unchanged) when no bean has a non-empty
 * {@code probes()}.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public final class PulseSpiHealthGroupsPostProcessor implements HealthEndpointGroupsPostProcessor {

    private final String prefix;

    /** Set of check names that have an explicit {@code probes()} override. */
    private final Set<String> overriddenChecks;

    /** {@code groupName -> Set<checkName>} for the inverse: which checks belong in each group. */
    private final Map<String, Set<String>> membership;

    /**
     * @param prefix dot-suffixed prefix that identifies SPI bean names in the group's
     *               {@code isMember(name)} calls (e.g. {@code "pulseCustom."} or
     *               {@code "pulseReactive."}).
     * @param probesByName map of check name to its {@code probes()} return value. Empty map
     *                     causes the post-processor to short-circuit and return its input
     *                     unchanged.
     */
    public PulseSpiHealthGroupsPostProcessor(String prefix, Map<String, Set<String>> probesByName) {
        this.prefix = prefix;
        this.overriddenChecks = Set.copyOf(probesByName.keySet());
        this.membership = invert(probesByName);
    }

    private static Map<String, Set<String>> invert(Map<String, Set<String>> nameToProbes) {
        Map<String, Set<String>> probeToNames = new HashMap<>();
        nameToProbes.forEach((checkName, probes) -> probes.forEach(probe -> probeToNames
                .computeIfAbsent(probe, k -> new HashSet<>())
                .add(checkName)));
        return probeToNames;
    }

    @Override
    public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
        if (overriddenChecks.isEmpty()) {
            return groups;
        }
        return new WrappedGroups(groups);
    }

    private final class WrappedGroups implements HealthEndpointGroups {

        private final HealthEndpointGroups delegate;

        WrappedGroups(HealthEndpointGroups delegate) {
            this.delegate = delegate;
        }

        @Override
        public HealthEndpointGroup getPrimary() {
            return delegate.getPrimary();
        }

        @Override
        public Set<String> getNames() {
            return delegate.getNames();
        }

        @Override
        public @Nullable HealthEndpointGroup get(String name) {
            HealthEndpointGroup original = delegate.get(name);
            if (original == null) {
                return null;
            }
            return new WrappedGroup(name, original);
        }
    }

    private final class WrappedGroup implements HealthEndpointGroup {

        private final String groupName;

        private final HealthEndpointGroup delegate;

        WrappedGroup(String groupName, HealthEndpointGroup delegate) {
            this.groupName = groupName;
            this.delegate = delegate;
        }

        @Override
        public boolean isMember(String name) {
            if (name.startsWith(prefix)) {
                String checkName = name.substring(prefix.length());
                if (overriddenChecks.contains(checkName)) {
                    return membership.getOrDefault(groupName, Set.of()).contains(checkName);
                }
            }
            return delegate.isMember(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return delegate.showComponents(securityContext);
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return delegate.showDetails(securityContext);
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return delegate.getStatusAggregator();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return delegate.getHttpCodeStatusMapper();
        }

        @Override
        public @Nullable AdditionalHealthEndpointPath getAdditionalPath() {
            return delegate.getAdditionalPath();
        }
    }
}
