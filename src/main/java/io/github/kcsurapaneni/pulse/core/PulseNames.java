package io.github.kcsurapaneni.pulse.core;

/**
 * Shared validation for component names that appear under {@code /actuator/health}.
 *
 * <p>Spring Boot's {@code CompositeHealthContributor} rejects names containing {@code /} and we
 * enforce that here as a fast fail at bean creation time. Centralised so the rule can't drift
 * between modules.
 *
 * @author Krishna Chaitanya Surapaneni
 */
public final class PulseNames {

    private PulseNames() {
    }

    /**
     * Validate a component name. {@code kind} is used in error messages only (e.g.
     * {@code "mount point"}, {@code "Mule service"}).
     *
     * @throws IllegalStateException if the name is null, blank, or contains {@code '/'}
     */
    public static void validate(String name, String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(kind + " name must not be blank");
        }
        if (name.contains("/")) {
            throw new IllegalStateException(kind + " name must not contain '/': " + name);
        }
    }
}
