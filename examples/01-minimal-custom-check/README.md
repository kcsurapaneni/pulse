# Example 01 — Minimal custom Pulse check

The simplest possible Pulse usage: one bean implementing `PulseCheck`, auto-discovered and surfaced under `/actuator/health/pulseCustom/in-memory-cache`.

## What it shows

- How to implement the `PulseCheck` SPI in ~25 lines (`InMemoryCacheCheck.java`)
- That Pulse decorates every result with `latencyMs`, `lastSuccessAt`, `lastFailureAt` without you doing anything
- The two-line `application.yml` you need (`show-details: always` + `expose health`)

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the project's `../../mvnw`)
- A GitHub PAT with `read:packages` scope wired into `~/.m2/settings.xml` — see the top-level [README](../../README.md#install) for the full setup, including the `<server id="github-pulse">` entry

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

## See also

- Example 02: all built-in checks (mount, mule) + K8s probe routing
- Example 03: WebFlux + `ReactivePulseCheck` using `WebClient`
