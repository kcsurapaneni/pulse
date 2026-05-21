# Example 04 — OAuth2 health check against a Testcontainers Keycloak

A runnable demo of the `pulse.oauth2` check pointed at a **real IdP** — a Keycloak container started automatically by Spring Boot's Testcontainers integration. Clone, `mvn spring-boot:test-run`, hit `/actuator/health`, watch Pulse perform a live `client_credentials` handshake against Keycloak and report the result.

## What it shows

- How `pulse.oauth2` consumes credentials from an existing `spring.security.oauth2.client.registration.<id>` — no duplication into `pulse.*` config
- How a Keycloak Testcontainer is wired in via Spring Boot 3.1+'s `SpringApplication.from(...).with(...)` pattern, so the production app classpath stays free of test-only deps
- The `pulseOauth2.example-idp` contributor under `/actuator/health` reporting `UP` with `httpStatus`, `tokenType`, `expiresInSec`, `cached`, plus the universal `latencyMs` / `lastSuccessAt`

## Prerequisites

- Java 21+, Maven 3.9+
- **Docker** (any flavour: Docker Desktop, Colima, Rancher Desktop) — Testcontainers needs it to start Keycloak
- A GitHub PAT with `read:packages` in `~/.m2/settings.xml` (see [top-level README](../../README.md#install))

First run will pull the Keycloak image (~500 MB). Subsequent runs reuse the cached image.

## Run

```bash
cd examples/04-oauth2-keycloak
mvn spring-boot:test-run
```

The `test-run` goal (Spring Boot Maven Plugin 3.1+) launches the app with the test classpath active, so `TestcontainersConfiguration` boots the Keycloak container and injects the dynamic token URI before Spring Security's OAuth2 client config is bound.

You'll see ~30–60 seconds of Keycloak startup logs, then the app reports `Started OAuth2ExampleApplication in N seconds`.

## Verify

```bash
curl -s http://localhost:8080/actuator/health | jq '.components.oauth2'
```

Expected output:

```json
{
  "status": "UP",
  "components": {
    "example-idp": {
      "status": "UP",
      "details": {
        "registrationId": "example",
        "tokenUri": "http://localhost:NNNNN/realms/test/protocol/openid-connect/token",
        "clientId": "test-client",
        "authMethod": "client_secret_post",
        "httpStatus": 200,
        "cached": false,
        "tokenType": "Bearer",
        "expiresInSec": 300,
        "latencyMs": 42,
        "lastSuccessAt": "2026-05-20T..."
      }
    }
  }
}
```

Hit the endpoint a second time and watch `cached` flip to `true` and `expiresInSec` count down. Pulse refreshes the token at 80% of `min(token.expires_in, cache-ttl)` — see the OAuth2 section in the [top-level README](../../README.md#oauth2-check) for the full caching model.

## Try a failure

Edit `application.yml` and set the wrong secret:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          example:
            client-secret: WRONG-SECRET
```

Restart the app, re-curl:

```json
"example-idp": {
  "status": "DOWN",
  "details": {
    "httpStatus": 401,
    "error": "client_credentials handshake failed: unauthorized_client - Invalid client or Invalid client credentials",
    ...
  }
}
```

Notice the token value itself is never in `details` — Pulse never echoes access tokens into health output. And the IdP's error description is extracted into the `error` detail without dumping the raw response body (a deliberate guard against IdPs reflecting request headers back).

## Why Testcontainers in an example?

OAuth2 is the one Pulse check that can't be demonstrated against a public sandbox without leaking credentials or requiring a sign-up flow. Embedding Keycloak via Testcontainers gives clone-and-run UX without trading away security: every invocation gets a fresh isolated realm.

The production-app classpath stays free of Testcontainers — the container wiring lives entirely under `src/test/java` and is only activated by `spring-boot:test-run`. If you adapt this example for a real deployment, you'd point `spring.security.oauth2.client.provider.example.token-uri` at your actual IdP and delete the test-time wiring entirely; nothing else changes.

## See also

- Example 01: minimal `PulseCheck` SPI
- Example 02: built-in mount + mule checks with K8s probe routing
- Example 03: WebFlux + `ReactivePulseCheck`
- Pulse's own integration test against Keycloak, also via Testcontainers: [`OAuth2CheckKeycloakIT`](../../src/test/java/dev/kc/pulse/oauth2/OAuth2CheckKeycloakIT.java)
