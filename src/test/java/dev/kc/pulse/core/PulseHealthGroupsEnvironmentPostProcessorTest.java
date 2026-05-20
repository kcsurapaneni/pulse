package dev.kc.pulse.core;

import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class PulseHealthGroupsEnvironmentPostProcessorTest {

    private final PulseHealthGroupsEnvironmentPostProcessor processor =
            new PulseHealthGroupsEnvironmentPostProcessor();

    @Test
    void contributesPulseCustomToReadinessByDefault() {
        MockEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(includeFor(env, "readiness")).contains("readinessState", "pulseCustom");
        assertThat(env.getProperty("management.endpoint.health.group.liveness.include"))
                .as("custom shouldn't touch liveness with default config")
                .isNull();
    }

    @Test
    void contributesPulseReactiveToReadinessByDefault() {
        MockEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(includeFor(env, "readiness")).contains("readinessState", "pulseReactive");
    }

    @Test
    void emptyReactiveProbesListOptsOut() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.reactive.probes", "");
        env.setProperty("pulse.custom.probes", "");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
                .as("both custom + reactive opted out").isNull();
    }

    @Test
    void addsEnabledModulesToReadiness() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.mount.enabled", "true");
        env.setProperty("pulse.mule.enabled", "true");
        env.setProperty("pulse.oauth2.enabled", "true");

        processor.postProcessEnvironment(env, null);

        assertThat(includeFor(env, "readiness"))
                .contains("readinessState", "mount", "mule", "oauth2", "pulseCustom");
        assertThat(env.getProperty("management.endpoint.health.group.liveness.include"))
                .as("with default probes=[readiness], liveness shouldn't be touched")
                .isNull();
    }

    @Test
    void respectsPerModuleProbesOverride() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.mount.enabled", "true");
        env.setProperty("pulse.mount.probes", "liveness,readiness");
        env.setProperty("pulse.mule.enabled", "true");
        env.setProperty("pulse.mule.probes", "readiness");

        processor.postProcessEnvironment(env, null);

        assertThat(includeFor(env, "liveness")).contains("livenessState", "mount");
        assertThat(includeFor(env, "liveness")).doesNotContain("mule");
        assertThat(includeFor(env, "readiness")).contains("readinessState", "mount", "mule");
    }

    @Test
    void emptyProbesListOptsOut() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.mount.enabled", "true");
        env.setProperty("pulse.mount.probes", "");
        env.setProperty("pulse.custom.probes", "");
        env.setProperty("pulse.reactive.probes", "");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.endpoint.health.group.liveness.include"))
                .as("nothing opted in to liveness").isNull();
        assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
                .as("mount, custom, reactive all opted out")
                .isNull();
    }

    @Test
    void disabledModulesDoNotContribute() {
        MockEnvironment env = new MockEnvironment();
        // none enabled
        env.setProperty("pulse.custom.probes", "");
        env.setProperty("pulse.reactive.probes", "");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.endpoint.health.group.liveness.include")).isNull();
        assertThat(env.getProperty("management.endpoint.health.group.readiness.include")).isNull();
    }

    @Test
    void mergesIntoConsumerExistingIncludeList() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.mount.enabled", "true");
        env.setProperty("management.endpoint.health.group.readiness.include",
                "readinessState,db,kafka");

        processor.postProcessEnvironment(env, null);

        // Consumer's db, kafka preserved; readinessState preserved; mount + pulseCustom added.
        String include = includeFor(env, "readiness");
        assertThat(include).contains("readinessState", "db", "kafka", "mount", "pulseCustom");
    }

    @Test
    void usesNonAvailabilityProbeWithoutStateContributor() {
        // A consumer can route checks to custom groups too (e.g., "startup").
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pulse.mount.enabled", "true");
        env.setProperty("pulse.mount.probes", "startup");
        env.setProperty("pulse.custom.probes", "");  // silence custom

        processor.postProcessEnvironment(env, null);

        String startup = env.getProperty("management.endpoint.health.group.startup.include");
        assertThat(startup).isEqualTo("mount");
    }

    private static String includeFor(MockEnvironment env, String probe) {
        return env.getProperty("management.endpoint.health.group." + probe + ".include");
    }
}
