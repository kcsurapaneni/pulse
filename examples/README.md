# Pulse — runnable examples

Three small Spring Boot apps that demonstrate how to consume Pulse. Each is a standalone Maven project pinned to a released version of `pulse-starter`; clone the repo, `cd` into one, and run `mvn spring-boot:run`.

| # | Folder | Stack | Demonstrates |
|---|---|---|---|
| 01 | [`01-minimal-custom-check`](01-minimal-custom-check/) | `spring-boot-starter-web` | The simplest possible use of Pulse: one `PulseCheck` bean surfaced under `/actuator/health/pulseCustom/in-memory-cache` |
| 02 | [`02-all-supported-checks`](02-all-supported-checks/) | `spring-boot-starter-web` | Built-in `mount` (against `/tmp` and `$HOME`) + `mule` (against `httpbin.org`) checks, with `pulse.<module>.probes` driving the difference between `/actuator/health/liveness` and `/actuator/health/readiness` |
| 03 | [`03-webflux-reactive`](03-webflux-reactive/) | `spring-boot-starter-webflux` | `ReactivePulseCheck` SPI using `WebClient` to ping a downstream over a fully non-blocking pipeline; surfaces under `/actuator/health/pulseReactive/httpbin` |
| 04 | [`04-oauth2-keycloak`](04-oauth2-keycloak/) | `spring-boot-starter-web` + `spring-boot-starter-oauth2-client` | `pulse.oauth2` check against a **real IdP** — Keycloak is started via Spring Boot's Testcontainers integration so the example runs with one command. Requires Docker. |

## Prerequisites (all examples)

1. **Java 21+** and **Maven 3.9+** on the path.
2. A **GitHub Personal Access Token** with the `read:packages` scope so Maven can resolve `dev.kc.pulse:pulse-starter` from GitHub Packages. Add a `<server>` entry to your `~/.m2/settings.xml`:

   ```xml
   <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
     <servers>
       <server>
         <id>github-pulse</id>
         <username>YOUR-GITHUB-USERNAME</username>
         <password>${env.GITHUB_TOKEN}</password>
       </server>
     </servers>
   </settings>
   ```

   Then `export GITHUB_TOKEN=...` before running `mvn`. The full setup (and Maven Central as a future alternative that removes this friction) is documented in the top-level [README](../README.md#install).

3. Internet access — examples 02 and 03 hit `httpbin.org`.

## How these stay in sync with `pulse-starter`

Each example pins to the most recently released version of Pulse (currently **0.4.0**). When a new Pulse release ships, the examples are bumped in the same PR cycle so a fresh clone always works against a real published artefact.

A dedicated CI workflow (`.github/workflows/examples-ci.yml`) compiles every example on push and PR — including a weekly run — so silent drift doesn't accumulate.

## Not covered (yet)

- OAuth2 (see note above)
- Custom `HttpClient` override beans (`muleHealthHttpClient`, `oauth2HealthHttpClient`)
- Programmatic group customisation via `HealthEndpointGroupsPostProcessor`

If you want examples for any of these, open an issue.
