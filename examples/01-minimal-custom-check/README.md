# Example 01 — Minimal custom Pulse check

The simplest possible Pulse usage: one bean implementing `PulseCheck`, auto-discovered and surfaced under `/actuator/health/pulseCustom/in-memory-cache`.

## What it shows

- How to implement the `PulseCheck` SPI in ~25 lines (`InMemoryCacheCheck.java`)
- That Pulse decorates every result with `latencyMs`, `lastSuccessAt`, `lastFailureAt` without you doing anything
- The two-line `application.yml` you need (`show-details: always` + `expose health`)

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the project's `../../mvnw`)
- Internet access — Maven will resolve `pulse-starter` from Maven Central

## Run

```bash
cd examples/01-minimal-custom-check
mvn spring-boot:run
```

Then in another terminal:

```bash
curl -s http://localhost:8080/actuator/health | jq
```

Expected output (truncated):

```json
{
  "status": "UP",
  "components": {
    "pulseCustom": {
      "status": "UP",
      "components": {
        "in-memory-cache": {
          "status": "UP",
          "details": {
            "cacheSize": 0,
            "latencyMs": 0,
            "lastSuccessAt": "2026-05-20T..."
          }
        }
      }
    },
    "ping": { "status": "UP" }
  }
}
```

## Try a failure

Edit `InMemoryCacheCheck.java` to deliberately mismatch:

```java
cache.put(probeKey, "ok");
String readBack = "definitely-wrong";   // simulate corruption
```

Restart, re-curl, observe:

```json
"in-memory-cache": {
  "status": "DOWN",
  "details": {
    "error": "read-back mismatch",
    "expected": "ok",
    "actual": "definitely-wrong",
    "latencyMs": 0,
    "lastFailureAt": "2026-05-20T..."
  }
}
```

The overall `status` flips to `DOWN` because any non-UP child propagates up the composite.

## Per-check overrides (Pulse 0.10.0+)

`InMemoryCacheCheck.java` carries two commented-out blocks demonstrating both per-check SPI overrides:

- **`checkTimeout()`** gives this one bean a longer (or shorter) wall-clock budget than the global `pulse.check-timeout`, without touching the cap for any other check.
- **`probes()`** overrides the module-level `pulse.custom.probes` for this specific bean — return e.g. `Set.of("liveness")` to make this single check pod-fatal while others stay on readiness only.

Uncomment whichever you want to experiment with and restart. Full trade-off in the top-level README's "Custom checks (SPI) → Per-check overrides" section.

## See also

- Example 02: all built-in checks (mount, mule) + K8s probe routing
- Example 03: WebFlux + `ReactivePulseCheck` using `WebClient`
