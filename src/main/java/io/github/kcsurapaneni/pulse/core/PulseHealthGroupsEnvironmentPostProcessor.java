package io.github.kcsurapaneni.pulse.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Wires Pulse contributors into Spring Boot's K8s probe groups (liveness, readiness) based on
 * per-module {@code probes} configuration. Runs as an {@link EnvironmentPostProcessor} so it can
 * mutate the environment before actuator's group config is built.
 *
 * <p>For each enabled Pulse module, the contributor name (e.g. {@code mount}) is added to each
 * probe group listed in {@code pulse.<module>.probes}. The {@code pulseCustom} composite always
 * contributes per {@code pulse.custom.probes}; setting it to an empty list opts out.
 *
 * <p>Existing values of {@code management.endpoint.health.group.<probe>.include} (whether set by
 * the consumer or defaulted by Spring Boot) are merged, not replaced. When the consumer hasn't set
 * the property and the probe is one of Spring Boot's built-in availability groups (liveness /
 * readiness), the corresponding {@code <probe>State} contributor is added back so the pod's
 * built-in availability state still feeds the probe.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public class PulseHealthGroupsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "pulse-health-groups";

    private static final Set<String> AVAILABILITY_GROUPS = Set.of("liveness", "readiness");

    private static final List<ModuleSpec> MODULES = List.of(
            new ModuleSpec("pulse.mount", "mount", true),
            new ModuleSpec("pulse.mule", "mule", true),
            new ModuleSpec("pulse.oauth2", "oauth2", true),
            new ModuleSpec("pulse.custom", "pulseCustom", false),
            new ModuleSpec("pulse.reactive", "pulseReactive", false));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Set<String>> additions = computeAdditions(env);
        if (additions.isEmpty()) {
            return;
        }
        Map<String, Object> merged = mergeIntoExistingGroups(env, additions);
        if (!merged.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, merged));
        }
    }

    private Map<String, Set<String>> computeAdditions(ConfigurableEnvironment env) {
        Binder binder = Binder.get(env);
        Map<String, Set<String>> additions = new HashMap<>();
        for (ModuleSpec module : MODULES) {
            if (module.gatedByEnabled()
                    && !binder.bind(module.propPrefix() + ".enabled", Bindable.of(Boolean.class))
                            .orElse(false)) {
                continue;
            }
            List<String> probes = binder.bind(module.propPrefix() + ".probes",
                    Bindable.listOf(String.class)).orElseGet(() -> List.of("readiness"));
            for (String probe : probes) {
                if (probe == null) {
                    continue;
                }
                String normalised = probe.trim();
                if (normalised.isEmpty()) {
                    continue;
                }
                additions.computeIfAbsent(normalised, k -> new LinkedHashSet<>())
                        .add(module.contributor());
            }
        }
        return additions;
    }

    private Map<String, Object> mergeIntoExistingGroups(ConfigurableEnvironment env,
            Map<String, Set<String>> additions) {
        Map<String, Object> merged = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : additions.entrySet()) {
            String probe = e.getKey();
            String key = "management.endpoint.health.group." + probe + ".include";
            Set<String> include = new LinkedHashSet<>();
            String existing = env.getProperty(key);
            if (existing != null && !existing.isBlank()) {
                Arrays.stream(existing.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(include::add);
            }
            else if (AVAILABILITY_GROUPS.contains(probe)) {
                // Preserve Spring Boot's default contributor for liveness/readiness when the
                // consumer hasn't set an explicit include list.
                include.add(probe + "State");
            }
            include.addAll(e.getValue());
            merged.put(key, String.join(",", include));
        }
        return merged;
    }

    private record ModuleSpec(String propPrefix, String contributor, boolean gatedByEnabled) {
    }
}
