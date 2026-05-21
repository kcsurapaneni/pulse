# Example 02 — All supported checks + K8s probe routing

Enables the two built-in checks (mount + mule) and demonstrates how `pulse.<module>.probes` maps each contributor onto Kubernetes liveness vs readiness probe groups.

OAuth2 is intentionally skipped here — it needs a real IdP (or a Testcontainers Keycloak), which doesn't belong in a clone-and-run example. See the [`OAuth2CheckKeycloakIT`](../../src/test/java/dev/kc/pulse/oauth2/OAuth2CheckKeycloakIT.java) integration test for a runnable demo against a real Keycloak.

## What it shows

- Configuring the mount-point check against two real paths (`/tmp`, `${user.home}`)
- Configuring the Mule HTTP check against `httpbin.org` — one entry deliberately mismatched so you can see a `DOWN` result and the failure details
- Splitting K8s probe groups: mount → `[liveness, readiness]`, mule → `[readiness]` only
- The `pulse.check-timeout` outer deadline applied uniformly across both modules

## Prerequisites

- Java 21+, Maven 3.9+
- Internet access — Maven resolves `pulse-starter` from Maven Central, and the Mule entries hit `httpbin.org`

## Run

```bash
cd examples/02-all-supported-checks
mvn spring-boot:run
```

## Three things to look at

### 1. Overall `/actuator/health`

```bash
curl -s http://localhost:8080/actuator/health | jq
```

Expected (truncated):

```json
{
  "status": "DOWN",
  "components": {
    "mount": {
      "status": "UP",
      "components": {
        "tmp":  { "status": "UP", "details": { "path": "/tmp", "freeBytes": ..., "latencyMs": 1 }},
        "home": { "status": "UP", "details": { ... }}
      }
    },
    "mule": {
      "status": "DOWN",
      "components": {
        "httpbin-ok":    { "status": "UP",   "details": { "httpStatus": 200, ... }},
        "httpbin-flaky": { "status": "DOWN", "details": { "httpStatus": 503, "error": "unexpected status code" }}
      }
    }
  }
}
```

Overall flips to `DOWN` because `httpbin-flaky` is intentionally configured to expect 200 from a URL that returns 503.

### 2. Liveness — should remain `UP`

```bash
curl -s http://localhost:8080/actuator/health/liveness | jq
```

Only `livenessState` + `mount` participate here (per `pulse.mount.probes`). Mule isn't in this group, so its `DOWN` doesn't reach liveness. K8s won't restart the pod.

### 3. Readiness — should report `DOWN`

```bash
curl -s http://localhost:8080/actuator/health/readiness | jq
```

`readinessState` + `mount` + `mule` all contribute. Mule's `DOWN` drops the overall readiness to `DOWN`. K8s removes this pod from the service load balancer until Mule recovers — without restarting it.

## Knobs to try

- Fix the flaky entry: change `expected-status: 200` to `503` and watch readiness flip back to `UP`.
- Force a mount failure: change `min-free-bytes` to something absurd like `9999999999999`. Watch liveness flip to `DOWN`.
- Take Mule out of probes entirely: `pulse.mule.probes: []`. Mule still shows under `/actuator/health` overall but no longer affects K8s probes.

## See also

- Example 01: single `PulseCheck` SPI bean
- Example 03: WebFlux + `ReactivePulseCheck`
