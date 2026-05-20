# pulse

[![CI](https://github.com/kcsurapaneni/pulse/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kcsurapaneni/pulse/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/kcsurapaneni/pulse/branch/main/graph/badge.svg)](https://codecov.io/gh/kcsurapaneni/pulse)

Reusable Spring Boot health indicators for things Spring Boot Actuator doesn't ship out of the box:

| Check    | Component key      | Verifies                                                          |
|----------|--------------------|-------------------------------------------------------------------|
| Mount    | `mount.<name>`     | Path exists, is a directory, is readable, free space ≥ threshold  |
| Mule     | `mule.<name>`      | HTTP GET returns the expected status code                         |
| OAuth2   | `oauth2.<name>`    | `client_credentials` handshake against the token endpoint (cached)|
| Custom   | `pulseCustom.<name>` | Anything you implement via the `PulseCheck` SPI    |

All checks register as standard `HealthContributor`s and surface under `/actuator/health` alongside Spring Boot's built-in indicators.

## Requirements

- Spring Boot **4.0.x**
- Java **21+**
- `spring-boot-starter-actuator` on the consumer's classpath (this library declares it `optional` so it doesn't force itself on you)
- `spring-boot-starter-oauth2-client` and `jackson-databind` are required *only* if you enable the OAuth2 check — the library declares both `optional` and the auto-configuration backs off cleanly when they're absent

## Install

`pulse-starter` is published to **GitHub Packages**. Consumers need three pieces of config: the dependency + repository, authentication for Maven, and the actuator health endpoint enabled with details.

### 1. Add the dependency and repository

In your consumer app's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-pulse</id>
    <url>https://maven.pkg.github.com/kcsurapaneni/pulse</url>
  </repository>
</repositories>

<dependency>
  <groupId>dev.kc.pulse</groupId>
  <artifactId>pulse-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

You also need `spring-boot-starter-actuator` (this library declares it `optional` so it doesn't force itself on you):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Add `spring-boot-starter-oauth2-client` *only* if you enable the OAuth2 check.

### 2. Authenticate to GitHub Packages

GitHub Packages requires a personal access token even for public packages. Generate one at https://github.com/settings/tokens with the `read:packages` scope (only), then add a `<server>` entry to your `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github-pulse</id>                <!-- must match the <repository><id> above -->
      <username>YOUR-GITHUB-USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Keep the PAT in the `GITHUB_TOKEN` env var (`export GITHUB_TOKEN=...`) rather than hardcoded — easier to rotate, never written to disk. For CI runners, set `GITHUB_TOKEN` as a secret.

### 3. Expose the health endpoint with details

Without `show-details`, `/actuator/health` only reports `UP`/`DOWN` and the per-check diagnostics Pulse adds (`latencyMs`, `lastSuccessAt`, `lastFailureAt`, plus check-specific fields) won't surface:

```yaml
management:
  endpoint:
    health:
      show-details: always              # or 'when-authorized' for prod
  endpoints:
    web:
      exposure:
        include: health                  # ensure /actuator/health is exposed
```

Each Pulse check is **opt-in** via its own `enabled` flag — nothing is registered unless you turn it on. See the per-check sections below.

Pulse contributors also honour Spring Boot's standard kill-switch `management.health.<name>.enabled` (default `true`). Setting `management.health.mount.enabled=false`, for example, disables the `mount` contributor regardless of `pulse.mount.enabled`. This matches the convention Boot's own built-in indicators follow (`management.health.db.enabled`, `management.health.redis.enabled`, etc.) and lets you toggle Pulse on a per-environment basis through the same property namespace you already use.

## Hung-check protection

Every Pulse check runs under a global outer deadline. If `PulseCheck.check()` takes longer than this deadline — a degraded NFS mount, a stuck socket, a deadlocked custom check — the adapter returns `DOWN` with `details.error="check timed out after PT5S"` rather than blocking the entire `/actuator/health` response. Without this guard a single hung check can take down a Kubernetes liveness probe.

```yaml
pulse:
  check-timeout: 5s          # default; tune per environment
```

This is an **outer** deadline applied uniformly to every check. Module-specific request timeouts (`pulse.mule.timeout`, `pulse.oauth2.timeout`) still apply at the inner request level — `check-timeout` just caps the total so a missed inner timeout can't escalate into a blocked actuator response.

## Kubernetes probes (liveness vs readiness)

Spring Boot exposes two extra availability-aware endpoints in addition to `/actuator/health`:

- `/actuator/health/liveness` — used by K8s liveness probe. **Failure restarts the pod.**
- `/actuator/health/readiness` — used by K8s readiness probe. **Failure drops the pod from the load balancer** without restarting it.

Pulse wires each module into these probe groups based on a `probes` property per module. **All checks default to `[readiness]`** — downstream-dependency failures should drop traffic but not trigger restarts. Add `liveness` only for checks that genuinely indicate the *pod itself* is broken.

```yaml
pulse:
  mount:
    enabled: true
    probes: [liveness, readiness]      # opt in to liveness for a critical local mount
    points: [...]
  mule:
    enabled: true
    probes: [readiness]                # default — downstream HTTP outage shouldn't restart pod
    services: [...]
  oauth2:
    enabled: true
    probes: [readiness]                # default — IdP outage shouldn't restart pod
    providers: [...]
  custom:
    probes: [readiness]                # default — applies to all SPI (pulseCustom) checks
```

Each Pulse contributor (`mount`, `mule`, `oauth2`, `pulseCustom`) is appended to the configured group's `management.endpoint.health.group.<probe>.include` list — Spring Boot's defaults (`livenessState`, `readinessState`) and any consumer-set entries are preserved. Set `probes: []` to keep a module out of K8s probe groups entirely (it'll still show up under `/actuator/health`).

A typical K8s deployment manifest then uses:

```yaml
livenessProbe:
  httpGet:  { path: /actuator/health/liveness,  port: 8080 }
readinessProbe:
  httpGet:  { path: /actuator/health/readiness, port: 8080 }
```

## Mount-point check

Configure one or more mount points. The check is `DOWN` when any of: path is missing, isn't a directory, isn't readable, or free space falls below a configured threshold.

```yaml
pulse:
  mount:
    enabled: true
    points:
      - name: s-drive
        path: "S:/"
        min-free-bytes: 1073741824        # 1 GiB
      - name: shared
        path: "//fileserver/data"
        min-free-percent: 10
```

Configuration:

| Property                                    | Default | Description                                            |
|---------------------------------------------|---------|--------------------------------------------------------|
| `pulse.mount.enabled`               | `false` | Master switch for all mount checks                     |
| `pulse.mount.points[].name`         | —       | Component key under `mount.<name>`. No `/` allowed.    |
| `pulse.mount.points[].path`         | —       | Filesystem path to check. UNC paths supported on Windows. |
| `pulse.mount.points[].min-free-bytes`   | —   | Minimum free bytes. Omit to skip the byte threshold.   |
| `pulse.mount.points[].min-free-percent` | —   | Minimum free percent (0–100). Omit to skip.            |

Both thresholds are optional; setting neither means existence + readability only.

## Mule check

A configurable list of HTTP endpoints. Each entry is `UP` when a GET returns the `expected-status`.

```yaml
pulse:
  mule:
    enabled: true
    timeout: 2s
    services:
      - name: order-svc
        url: "https://mule.internal/order/health"
        expected-status: 200
      - name: invoice-svc
        url: "https://mule.internal/invoice/health"
```

Configuration:

| Property                                      | Default | Description                                            |
|-----------------------------------------------|---------|--------------------------------------------------------|
| `pulse.mule.enabled`                  | `false` | Master switch                                          |
| `pulse.mule.timeout`                  | `2s`    | Per-request and connect timeout                        |
| `pulse.mule.services[].name`          | —       | Component key under `mule.<name>`. No `/` allowed.     |
| `pulse.mule.services[].url`           | —       | Endpoint to GET                                        |
| `pulse.mule.services[].expected-status` | `200` | Status code that counts as healthy                     |

The shared `HttpClient` is exposed as bean `muleHealthHttpClient`; supply your own bean of that name to override (custom TLS, proxy, etc.).

## OAuth2 check

This check **reuses your existing Spring Security `ClientRegistration` config** — you do not duplicate `client-id` / `client-secret` / `token-uri` into `pulse` config. Each provider entry just points at a registration id from `spring.security.oauth2.client.registration.*`. Rotating a secret in the existing config flows to the health check automatically — the `ClientRegistration` is re-resolved on every probe.

For each provider, the library performs a real `client_credentials` handshake against the token endpoint. The access token is cached and refreshed at **80%** of `min(token.expires_in, cache-ttl)` — so a typical 1-hour IdP token with the default 5-minute `cache-ttl` triggers a fresh handshake roughly every 4 minutes. The token value itself is **never** included in health details.

```yaml
# Your existing Spring Security OAuth2 client config — unchanged
spring:
  security:
    oauth2:
      client:
        registration:
          okta:
            client-id: ${OKTA_CLIENT_ID}
            client-secret: ${OKTA_CLIENT_SECRET}
            authorization-grant-type: client_credentials
            scope: internal
        provider:
          okta:
            token-uri: https://example.okta.com/oauth2/v1/token

# pulse just says "health-check this registration"
pulse:
  oauth2:
    enabled: true
    timeout: 3s
    providers:
      - name: okta
        registration-id: okta
        cache-ttl: 5m
```

Configuration:

| Property                                          | Default | Description                                              |
|---------------------------------------------------|---------|----------------------------------------------------------|
| `pulse.oauth2.enabled`                    | `false` | Master switch                                            |
| `pulse.oauth2.timeout`                    | `3s`    | Per-request and connect timeout                          |
| `pulse.oauth2.providers[].name`           | —       | Component key under `oauth2.<name>`                      |
| `pulse.oauth2.providers[].registration-id`| —       | Id of an existing `spring.security.oauth2.client.registration.<id>` |
| `pulse.oauth2.providers[].cache-ttl`      | `5m`    | Upper bound on token reuse (always capped by token expiry) |

The check honours the registration's `client-authentication-method`: `client_secret_basic` sends creds in the `Authorization: Basic …` header; `client_secret_post` (default) sends them in the form body. JWT-based methods aren't supported.

If the `registration-id` doesn't exist in the consumer's `ClientRegistrationRepository`, or the registration is missing `client-id` / `client-secret` / `token-uri`, the check reports `DOWN` with an explanatory `error` detail rather than failing at startup — secrets that haven't been wired up yet are usually a runtime problem, not a config-time one.

The shared `HttpClient` is exposed as bean `oauth2HealthHttpClient` (separate from `muleHealthHttpClient` so their timeouts don't conflict).

## Custom checks (SPI)

Implement `PulseCheck` and register your implementation as a Spring bean. The library auto-discovers it and surfaces it under `pulseCustom.<name>`.

```java
@Component
class PaymentsBackendCheck implements PulseCheck {
    private final PaymentsClient client;

    PaymentsBackendCheck(PaymentsClient client) {
        this.client = client;
    }

    @Override public String name() { return "payments"; }

    @Override public Health check() {
        var status = client.ping();
        if (status.ok()) {
            return Health.up().withDetail("version", status.version()).build();
        }
        return Health.down().withDetail("reason", status.message()).build();
    }
}
```

Each `PulseCheck` is wrapped in an adapter that automatically adds:

- `latencyMs` — wall-clock time for `check()`
- `lastSuccessAt` — timestamp of the most recent `UP` result
- `lastFailureAt` — timestamp of the most recent non-`UP` result or thrown exception

You don't need to thread these through yourself.

## Reactive checks (WebFlux)

WebFlux apps can run consumer-defined checks fully on the reactive scheduler — no `Schedulers.boundedElastic()` round-trip. Implement `ReactivePulseCheck` instead of `PulseCheck`; the library auto-discovers your bean and surfaces it under `pulseReactive.<name>`. Activates only when `reactor-core` is on the classpath, so non-WebFlux apps pay nothing.

```java
@Component
class PaymentsBackendReactiveCheck implements ReactivePulseCheck {
    private final WebClient client;

    PaymentsBackendReactiveCheck(WebClient client) {
        this.client = client;
    }

    @Override public String name() { return "payments"; }

    @Override public Mono<Health> check() {
        return client.get().uri("/healthz").retrieve()
                .toBodilessEntity()
                .map(resp -> Health.up().withDetail("status", resp.getStatusCode().value()).build())
                .onErrorResume(ex -> Mono.just(Health.down().withDetail("reason", ex.getMessage()).build()));
    }
}
```

Same decoration as the blocking SPI (`latencyMs` / `lastSuccessAt` / `lastFailureAt`), same outer deadline (`pulse.check-timeout`), same K8s probe model — but with its own routing property `pulse.reactive.probes` so blocking and reactive checks can be routed independently if needed:

```yaml
pulse:
  reactive:
    probes: [readiness]   # default
```

The built-in `mount` / `mule` / `oauth2` contributors stay blocking. In a WebFlux app, Spring Boot wraps them onto `Schedulers.boundedElastic()` automatically — they still work, they just consume a worker thread per probe. Reactive variants for those built-ins are not on the current roadmap.

## Sample `/actuator/health` output

With all four kinds of checks active:

```json
{
  "status": "UP",
  "components": {
    "mount": {
      "status": "UP",
      "components": {
        "s-drive": {
          "status": "UP",
          "details": {
            "path": "S:/", "totalBytes": 500107862016, "freeBytes": 312000000000,
            "latencyMs": 2, "lastSuccessAt": "2026-05-13T14:21:09Z"
          }
        }
      }
    },
    "mule": {
      "status": "UP",
      "components": {
        "order-svc": {
          "status": "UP",
          "details": {
            "url": "https://mule.internal/order/health",
            "expectedStatus": 200, "httpStatus": 200,
            "latencyMs": 41, "lastSuccessAt": "2026-05-13T14:21:09Z"
          }
        }
      }
    },
    "oauth2": {
      "status": "UP",
      "components": {
        "okta": {
          "status": "UP",
          "details": {
            "registrationId": "okta",
            "tokenUri": "https://example.okta.com/oauth2/v1/token",
            "clientId": "abc123", "httpStatus": 200,
            "authMethod": "client_secret_post",
            "cached": false, "tokenType": "Bearer", "expiresInSec": 3600,
            "latencyMs": 84, "lastSuccessAt": "2026-05-14T14:21:09Z"
          }
        }
      }
    },
    "pulseCustom": {
      "status": "UP",
      "components": {
        "payments": {
          "status": "UP",
          "details": {
            "version": "2026.05.1",
            "latencyMs": 12, "lastSuccessAt": "2026-05-13T14:21:09Z"
          }
        }
      }
    }
  }
}
```

The nested `details` fields require `management.endpoint.health.show-details: always` (see [Install](#install) step 3).

## Failure mode quick reference

| Situation                                              | Status | Where to look                              |
|--------------------------------------------------------|--------|--------------------------------------------|
| Mount path missing / not a directory / not readable    | `DOWN` | `details.error`                            |
| Mount free space below threshold                       | `DOWN` | `details.error`, `details.threshold`       |
| Mule endpoint returns wrong status                     | `DOWN` | `details.httpStatus`                       |
| Mule endpoint unreachable / times out                  | `DOWN` | `details.error`                            |
| OAuth2 `registration-id` not in `ClientRegistrationRepository` | `DOWN` | `details.error`                  |
| OAuth2 token endpoint returns 4xx                      | `DOWN` | `details.httpStatus`, `details.error`      |
| OAuth2 token endpoint unreachable                      | `DOWN` | `details.error`                            |
| OAuth2 success response is unparseable                 | `DOWN` | `details.error`                            |
| SPI `check()` throws                                   | `DOWN` | `details.error` (exception captured)       |

## Changelog

Release notes and version-to-version diffs live in [CHANGELOG.md](CHANGELOG.md).

## Building

```bash
./mvnw test       # unit + slice tests (~3s)
./mvnw verify     # adds integration tests; requires Docker for Keycloak
```

Integration tests live in `*IT.java` files and run under Failsafe:

- `OAuth2CheckKeycloakIT` — real Keycloak 26.x via Testcontainers, realm imported from `src/test/resources/realm-test.json`
- `MuleCheckWireMockIT` — in-JVM WireMock stubs

The first `verify` run downloads the Keycloak image (~500 MB).
