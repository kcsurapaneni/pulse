package com.example.pulse.minimal;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.kcsurapaneni.pulse.core.PulseCheck;

import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Verifies a tiny in-memory cache responds to write / read / remove. Stand-in for any
 * pod-local resource you want to assert about: a connection pool's free count, a
 * cached config blob's last-refresh timestamp, etc.
 *
 * <p>Pulse auto-discovers this as a {@link PulseCheck} bean and surfaces it under
 * {@code /actuator/health/pulseCustom/in-memory-cache}, decorated with
 * {@code latencyMs} / {@code lastSuccessAt} / {@code lastFailureAt} timestamps.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@Component
class InMemoryCacheCheck implements PulseCheck {

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "in-memory-cache";
    }

    @Override
    public Health check() {
        String probeKey = "probe-" + UUID.randomUUID();
        cache.put(probeKey, "ok");
        String readBack = cache.get(probeKey);
        cache.remove(probeKey);

        if (!"ok".equals(readBack)) {
            return Health.down()
                    .withDetail("error", "read-back mismatch")
                    .withDetail("expected", "ok")
                    .withDetail("actual", readBack)
                    .build();
        }
        return Health.up()
                .withDetail("cacheSize", cache.size())
                .build();
    }

    // --- Optional: per-check SPI overrides (opt-in, since Pulse 0.10.0) ---
    //
    // Both default methods on PulseCheck can be overridden per-bean. Uncomment either of
    // these to see the effect.
    //
    // 1. checkTimeout(): give this specific check more (or less) wall-clock budget than
    //    the global pulse.check-timeout. The adapter samples the override once at
    //    construction. Return null (the default) to inherit the global.
    //
    //   @Override
    //   public java.time.Duration checkTimeout() {
    //       return java.time.Duration.ofSeconds(15);   // null = inherit pulse.check-timeout
    //   }
    //
    // 2. probes(): override (NOT augment) the module-level pulse.custom.probes for this
    //    one bean. Non-empty means this contributor appears in exactly the named K8s
    //    probe groups, regardless of what pulse.custom.probes says. Return Set.of() (the
    //    default) to inherit. Useful when a particular SPI check is pod-fatal (belongs in
    //    liveness) and others aren't (readiness only) — no hand-written composite needed.
    //
    //   @Override
    //   public java.util.Set<String> probes() {
    //       return java.util.Set.of("liveness");        // empty = inherit pulse.custom.probes
    //   }
    //
    // Trade-offs + full table live in the top-level README under
    // "Custom checks (SPI) → Per-check overrides".
}
