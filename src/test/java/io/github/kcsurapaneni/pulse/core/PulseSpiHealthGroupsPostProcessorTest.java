package io.github.kcsurapaneni.pulse.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseSpiHealthGroupsPostProcessorTest {

    @Test
    void noOpWhenNoOverrides() {
        PulseSpiHealthGroupsPostProcessor processor = new PulseSpiHealthGroupsPostProcessor(
                "pulseCustom.", Map.of());
        HealthEndpointGroups input = stubGroups(Map.of("readiness", group(true)));

        HealthEndpointGroups output = processor.postProcessHealthEndpointGroups(input);

        // Same instance: no-op, no decoration.
        assertThat(output).isSameAs(input);
    }

    @Test
    void overrideAddsToTargetGroup() {
        // Bean foo declares probes()={liveness}. Underlying readiness group considers
        // pulseCustom.foo a member (parent inheritance) — but foo's override excludes it
        // from readiness because the override is exhaustive, not additive.
        Map<String, Set<String>> overrides = Map.of("foo", Set.of("liveness"));
        PulseSpiHealthGroupsPostProcessor processor = new PulseSpiHealthGroupsPostProcessor(
                "pulseCustom.", overrides);

        HealthEndpointGroup liveness = group(false); // base "no" — only foo's override flips it
        HealthEndpointGroup readiness = group(true); // base "yes" — only foo's override flips it
        HealthEndpointGroups input = stubGroups(Map.of(
                "liveness", liveness,
                "readiness", readiness));

        HealthEndpointGroups output = processor.postProcessHealthEndpointGroups(input);

        // Membership for foo:
        assertThat(output.get("liveness").isMember("pulseCustom.foo")).isTrue();
        assertThat(output.get("readiness").isMember("pulseCustom.foo")).isFalse();
        // Non-overridden bean falls back to underlying group's answer:
        assertThat(output.get("liveness").isMember("pulseCustom.other")).isFalse();
        assertThat(output.get("readiness").isMember("pulseCustom.other")).isTrue();
        // Unrelated names always defer:
        assertThat(output.get("readiness").isMember("livenessState")).isTrue();
    }

    @Test
    void wrongPrefixIsIgnored() {
        // Configured for blocking — must NOT touch pulseReactive.foo membership.
        PulseSpiHealthGroupsPostProcessor processor = new PulseSpiHealthGroupsPostProcessor(
                "pulseCustom.", Map.of("foo", Set.of("liveness")));
        HealthEndpointGroup liveness = group(false);
        HealthEndpointGroups output = processor.postProcessHealthEndpointGroups(
                stubGroups(Map.of("liveness", liveness)));

        // pulseReactive.foo not in our scope — defers to underlying (false).
        assertThat(output.get("liveness").isMember("pulseReactive.foo")).isFalse();
        // pulseCustom.foo is in our scope and override targets liveness — true.
        assertThat(output.get("liveness").isMember("pulseCustom.foo")).isTrue();
    }

    @Test
    void unknownGroupReturnsNull() {
        PulseSpiHealthGroupsPostProcessor processor = new PulseSpiHealthGroupsPostProcessor(
                "pulseCustom.", Map.of("foo", Set.of("liveness")));
        HealthEndpointGroups output = processor.postProcessHealthEndpointGroups(stubGroups(Map.of()));

        assertThat(output.get("nonexistent")).isNull();
    }

    @Test
    void reactivePrefixWorksTheSameWay() {
        PulseSpiHealthGroupsPostProcessor processor = new PulseSpiHealthGroupsPostProcessor(
                "pulseReactive.", Map.of("downstream", Set.of("readiness")));
        HealthEndpointGroup readiness = group(false);

        HealthEndpointGroups output = processor.postProcessHealthEndpointGroups(
                stubGroups(Map.of("readiness", readiness)));

        assertThat(output.get("readiness").isMember("pulseReactive.downstream")).isTrue();
        assertThat(output.get("readiness").isMember("pulseReactive.other")).isFalse();
    }

    // ---- minimal stubs ----

    private static HealthEndpointGroups stubGroups(Map<String, HealthEndpointGroup> map) {
        Map<String, HealthEndpointGroup> defensive = new LinkedHashMap<>(map);
        return new HealthEndpointGroups() {
            @Override
            public HealthEndpointGroup getPrimary() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> getNames() {
                return defensive.keySet();
            }

            @Override
            public @Nullable HealthEndpointGroup get(String name) {
                return defensive.get(name);
            }
        };
    }

    private static HealthEndpointGroup group(boolean defaultMembership) {
        return new HealthEndpointGroup() {
            @Override
            public boolean isMember(String name) {
                return defaultMembership;
            }

            @Override
            public boolean showComponents(SecurityContext securityContext) {
                return true;
            }

            @Override
            public boolean showDetails(SecurityContext securityContext) {
                return true;
            }

            @Override
            public StatusAggregator getStatusAggregator() {
                return null;
            }

            @Override
            public HttpCodeStatusMapper getHttpCodeStatusMapper() {
                return null;
            }

            @Override
            public @Nullable AdditionalHealthEndpointPath getAdditionalPath() {
                return null;
            }
        };
    }
}
