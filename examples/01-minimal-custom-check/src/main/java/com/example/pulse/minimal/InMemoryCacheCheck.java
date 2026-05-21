package com.example.pulse.minimal;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.kc.pulse.core.PulseCheck;

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
}
