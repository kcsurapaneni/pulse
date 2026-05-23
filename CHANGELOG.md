# Changelog

All notable changes to Pulse are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Dedicated `pulseHealthExecutor` bean** for the outer-deadline timeout enforcement in
  `PulseCheckAdapter`. Default is `Executors.newVirtualThreadPerTaskExecutor()` —
  Java 21 makes per-check virtual threads effectively free, and isolating the work from
  `ForkJoinPool.commonPool` means a hung check can't starve parallel streams,
  application-side `CompletableFuture` calls, or Spring's own infrastructure (and vice
  versa). Override by declaring your own bean named `pulseHealthExecutor` (e.g. a bounded
  platform-thread pool with Micrometer instrumentation) — Spring picks up the override via
  `@ConditionalOnMissingBean`. Cleanly shut down on context close via Spring's
  auto-detection of `ExecutorService#close()`.
- Public constant `PulseAutoConfiguration.HEALTH_EXECUTOR_BEAN_NAME` so consumers can
  reference the bean name from a fixed source instead of a literal string.

### Changed
- `PulseCheckAdapter` gains a 6-arg constructor that accepts a `java.util.concurrent.Executor`.
  The existing 3-arg and 5-arg constructors still compile — they delegate to the 6-arg form
  with `ForkJoinPool.commonPool()` as the fallback. All four internal call sites
  (`PulseAutoConfiguration`, `MountPointAutoConfiguration`, `MuleAutoConfiguration`,
  `OAuth2AutoConfiguration`) now inject the `pulseHealthExecutor` bean. The reactive adapter
  is unaffected — it uses `Mono.timeout` rather than `CompletableFuture`.

## [0.10.0] — 2026-05-21

Two non-breaking opt-ins land on the SPI plus a cleanup pass on the examples. Existing
consumers see no behavioural change; new `default` methods on `PulseCheck` and
`ReactivePulseCheck` let one specific bean override the global check timeout and the
module-level K8s probe routing without rolling a hand-written composite.

### Added
- **Per-check `checkTimeout()` on the SPI.** New default method on `PulseCheck` and
  `ReactivePulseCheck`:
  ```java
  default Duration checkTimeout() { return null; }
  ```
  Return non-null to cap one specific check's wall-clock time at a value different from the
  global `pulse.check-timeout`. Useful when one check legitimately needs longer than the rest
  (a paginated downstream query, a hostile-network probe) without slackening the global cap for
  every other check. The adapter samples the value once at construction.
- **Per-check `probes()` on the SPI.** New default method on `PulseCheck` and
  `ReactivePulseCheck`:
  ```java
  default Set<String> probes() { return Set.of(); }
  ```
  Return a non-empty set to **override** (not augment) the module-level `pulse.custom.probes`
  (or `pulse.reactive.probes`) for this specific bean — the contributor will appear in exactly
  the named K8s probe groups (e.g. `{"liveness"}` or `{"liveness", "readiness"}`) regardless of
  the module-level setting. Default empty set inherits. Lets consumers mix one pod-fatal SPI
  check (liveness) with non-pod-fatal ones (readiness only) without rolling their own composite.
  Implemented as a new `PulseSpiHealthGroupsPostProcessor` bean that wraps Spring Boot's
  `HealthEndpointGroups` at startup. No-op when no SPI bean has a non-empty `probes()`.

### Changed (examples only)
- **Examples 02 and 03 are now self-contained.** Previously
  `examples/02-all-supported-checks` pointed two Mule check entries at
  `https://httpbin.org/status/200` and `.../503`, and
  `examples/03-webflux-reactive` shipped a `HttpbinReactiveCheck` hitting the same
  third party via `WebClient`. Both have been replaced with an in-process
  `MockStatusController` (`GET /mock/status/{code}` returning the requested
  status). The example apps now run with zero external network — relevant on
  air-gapped networks and removes a flakiness source from `examples-ci`. The
  reactive check class was renamed `HttpbinReactiveCheck` →
  `DownstreamReactiveCheck` and its bean from `httpbinClient` →
  `downstreamClient`; component keys under `/actuator/health` move from
  `httpbin-ok` / `httpbin-flaky` / `httpbin` to `local-ok` / `local-flaky` /
  `downstream`. **Library API itself is unchanged.**

## [0.9.0] — 2026-05-21

Internal refactor with better error messages and one small new transitive dep. No
behavioural change for valid configs; misconfigured configs now fail with the full
property path included automatically.

### Changed
- **Property validation now uses Bean Validation** (`jakarta.validation`) instead of three
  hand-rolled `validate(...)` methods that lived in the module auto-configs. `pulse.mount`,
  `pulse.mule`, and `pulse.oauth2` properties classes are now `@Validated`, with `@NotBlank`
  on required string fields, `@Pattern("[^/]+")` on names (Spring Boot's
  `CompositeHealthContributor` rejects `/` in component keys), and `@Valid` on nested lists.
  Misconfigured properties now fail at bind time with the **full property path automatically
  included in the error message** (e.g. `pulse.mount.points[0].path: must not be blank`)
  rather than the previously hand-formatted strings. Behaviour is unchanged for valid configs.
- **Added `spring-boot-starter-validation`** as a non-optional dependency (~500 KB on the
  classpath, brings Hibernate Validator + `jakarta.validation` API). Required for the
  `@Validated` bind-time check; consumers who already pull validation via another starter
  (web, etc.) won't see a new transitive.
- **Removed `validate(...)` methods** from `MountPointAutoConfiguration`,
  `MuleAutoConfiguration`, and `OAuth2AutoConfiguration` — replaced entirely by the
  declarative annotations on the properties classes. `PulseNames.validate` stays in place
  for the SPI auto-configs (`PulseAutoConfiguration`, `PulseReactiveAutoConfiguration`)
  where the name comes from a Java method, not a configuration property.

## [0.8.0] — 2026-05-21

First non-breaking observability landing. Existing consumers see no behavioural change
— Pulse just starts publishing structured signals through whatever observability stack
the app already has configured.

### Added
- **Observability via Micrometer Observation API.** Every Pulse check now records a
  `pulse.check` Observation tagged with `name` (the check's component key, e.g. `okta`)
  and `kind` (`mount` / `mule` / `oauth2` / `custom` / `reactive`). Consumers wiring an
  OTel / OTLP / Prometheus / Datadog exporter at the application level pick up Pulse's
  signals automatically — metrics, traces, and bridged logs flow through whatever
  registry Spring Boot's observability stack is configured with. When no registry is
  configured the Observation is a no-op (zero cost). Implemented via the existing
  `io.micrometer:micrometer-observation` dependency that Spring Boot Actuator already
  brings transitively; no new optional dep.
- **Transition logging.** A check flipping UP → DOWN now emits a `WARN`-level log line
  with the check name, kind, status, and details; a DOWN → UP recovery emits at `INFO`.
  The first probe after startup never logs (no prior state to compare). Pulse previously
  had zero loggers in `src/main/java/`; this is the first.
- **Shared `PulseCheckTelemetry`** in `core` carrying last-success / last-failure /
  last-status tracking + decoration. Both `PulseCheckAdapter` and
  `ReactivePulseCheckAdapter` now delegate to it, removing the duplication between the
  blocking and reactive adapters. The class is `public` so future SPI work
  (per-check timeout, per-check probes) has a single integration point.

### Changed
- `PulseCheckAdapter` and `ReactivePulseCheckAdapter` gain a `(kind, ObservationRegistry)`
  constructor pair alongside the existing `(check, clock, timeout)` form. The old
  constructors still compile and default to `kind="custom"` / `kind="reactive"` and a
  NOOP registry, so consumers building adapters directly (rare — typically Pulse builds
  them) are not broken.

### Fixed
- `PulseCheckAdapterTest.recoversFromPriorTimeoutOnNextProbe` previously slept for
  60 seconds inside the hung-check fixture, leaking a ghost `ForkJoinPool` worker for
  the full minute on every CI run. Reduced to 2 seconds — the test still asserts the
  adapter terminates inside the configured 200 ms timeout.

## [0.7.0] — 2026-05-21

Second non-breaking OAuth2 opt-in in two releases. Existing consumers see no behavioural
change; opting in flips the meaning of `/actuator/health` for the OAuth2 check from
"is the IdP reachable right now" to "do we hold a usable token".

### Added
- **`pulse.oauth2.providers[].on-transient-failure`** controls how transient handshake
  failures interact with the cached token in `handshake` mode. Default `down` preserves
  the existing behaviour — transient errors clear the cache and report `DOWN`. Opt-in
  `stale` returns the still-naturally-valid cached token with `stale: true` and a
  `staleReason` detail until the token reaches its IdP-reported natural expiry. Transient
  failures include `IOException` (DNS / TLS / connect / read timeout), HTTP 5xx, and
  HTTP 429. HTTP 4xx other than 429 (401 / 403 / 400) always clears the cache and
  reports `DOWN` regardless of this setting — the IdP explicitly rejected the request,
  almost always credentials are wrong. Non-breaking: existing consumers see no change.

## [0.6.0] — 2026-05-20

A non-breaking opt-in for the OAuth2 check. Existing consumers see no behavioural
change; opting in trades credential-validity coverage for a lighter probe.

### Added
- **`pulse.oauth2.providers[].mode`** controls validation depth on every probe. Default
  `handshake` preserves the existing behaviour (real `client_credentials` call against the
  token endpoint, validates both IdP availability and credential validity). Opt-in `reachable`
  GETs the OIDC discovery document only — lighter, no credentials exercised, useful when
  another monitor catches credential breakage or when the IdP `client_credentials` flow
  isn't actually enabled for the registration. Non-breaking: existing consumers see no
  behavioural change.
- **`pulse.oauth2.providers[].discovery-uri`** explicit override for `reachable` mode.
  Otherwise the URL is derived as `<issuer-uri>/.well-known/openid-configuration` from
  Spring Security's `spring.security.oauth2.client.provider.<id>.issuer-uri`. Provides
  an escape hatch for registrations that configure `token-uri` directly without an
  `issuer-uri`.

## [0.5.0] — 2026-05-20

Pulse is now on **Maven Central**. The GitHub Packages PAT / `settings.xml` setup
goes away for consumers — a single `<dependency>` block resolves the library, just
like Spring Boot or Jackson. Costs are a new groupId (`io.github.kcsurapaneni`)
and migrated Java packages (`io.github.kcsurapaneni.pulse.*`); both are breaking
on the 0.4.0 → 0.5.0 hop and stay stable after.

### Changed (breaking)
- **`groupId` is now `io.github.kcsurapaneni`** (was `dev.kc.pulse`). Required for
  Maven Central publication, which only verifies namespaces tied to a domain or a
  GitHub account.
- **Java packages migrated** from `dev.kc.pulse.*` to `io.github.kcsurapaneni.pulse.*`.
  Consumers referencing Pulse SPI types directly (`PulseCheck`,
  `ReactivePulseCheck`, etc.) update their `import` statements once on the move
  from 0.4.0 → 0.5.0.

  Migration in a consumer pom:
  ```diff
  - <groupId>dev.kc.pulse</groupId>
  + <groupId>io.github.kcsurapaneni</groupId>
    <artifactId>pulse-starter</artifactId>
  - <version>0.4.0</version>
  + <version>0.5.0</version>
  ```

  Migration in consumer source:
  ```diff
  - import dev.kc.pulse.core.PulseCheck;
  + import io.github.kcsurapaneni.pulse.core.PulseCheck;
  ```

  All 0.1.0–0.4.0 artefacts remain accessible from GitHub Packages under the
  original `dev.kc.pulse` coordinates.

### Added
- POM metadata required by Maven Central: `<url>`, `<licenses>` (Apache-2.0),
  `<developers>`, `<scm>`. Verbatim Apache 2.0 `LICENSE` file at the repo root.
- Maven Central publishing pipeline. A `release` profile in `pom.xml` wires
  `maven-gpg-plugin` (signs every artefact; required by Central) and
  `central-publishing-maven-plugin` 0.7.0 (handles upload + staging + release
  via the Central Portal API). The `maven-publish.yml` workflow now imports
  the GPG key, runs `./mvnw verify`, then `./mvnw deploy -Prelease`. Credentials
  come from the `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` / `GPG_PRIVATE_KEY` /
  `GPG_PASSPHRASE` GitHub secrets. The workflow blocks until Central reports
  the artefact is publicly resolvable (`<waitUntil>published</waitUntil>`).
- Runnable example projects under `examples/`. Four standalone Maven apps pinned to
  `pulse-starter:0.4.0`, demonstrating (1) the minimal `PulseCheck` SPI, (2) the
  built-in `mount` + `mule` checks with K8s probe routing, (3) WebFlux +
  `ReactivePulseCheck`, and (4) `pulse.oauth2` against a real Keycloak started via
  Spring Boot's Testcontainers integration (`spring-boot:test-run`). Top-level
  `examples/README.md` indexes them; a dedicated `examples-ci.yml` workflow compiles
  each on PR / push / weekly so silent drift doesn't accumulate. Not part of the
  parent `mvn verify` — keeps the main CI fast.

## [0.4.0] — 2026-05-20

A reactive SPI and an IDE-quality-of-life pass. WebFlux apps now have a first-class
way to write Pulse checks that don't tie up a worker thread; consumers writing
`application.yml` get hover descriptions and value hints on every `pulse.*` property.

### Added
- `ReactivePulseCheck` SPI for WebFlux-native consumer-defined checks. Implementations
  return `Mono<Health>` and are auto-discovered into a new `pulseReactive` composite
  contributor that surfaces under `/actuator/health/pulseReactive.<name>`. Activates
  only when `reactor-core` is on the classpath (gate: `@ConditionalOnClass(Mono.class)`)
  so non-WebFlux apps pay nothing. Same decoration as the blocking SPI
  (`latencyMs` / `lastSuccessAt` / `lastFailureAt`), same `pulse.check-timeout` outer
  deadline (applied via `Mono.timeout`), and new `pulse.reactive.probes` property
  (default `[readiness]`) for independent K8s probe-group routing from the blocking
  `pulse.custom.probes`. Adds `reactor-core` as an optional dependency.
- IDE configuration-metadata now ships with descriptions on every `pulse.*` property
  and `liveness` / `readiness` value hints on each `*.probes` setting. Hover tooltips
  in IntelliJ/VS Code show what each key does; typing `pulse.mount.probes:` offers
  the two valid values as completions. Implemented via field-level Javadoc on
  `MountPointProperties` / `MuleProperties` / `OAuth2Properties` (inner classes
  included) plus a curated `META-INF/additional-spring-configuration-metadata.json`
  merged with the generator output by `spring-boot-configuration-processor`.

## [0.3.0] — 2026-05-19

Adds a Spring-idiomatic kill-switch for Pulse contributors so consumers can disable
them through the standard `management.health.*` namespace they already use for
Boot's built-in indicators. Plus a dependency-and-tooling refresh: JaCoCo coverage
into Codecov, Dependabot automation, a canonical CHANGELOG (this file), the
Testcontainers 2.x major bump, and SSH-signed commits from this release onward.

### Added
- Spring's standard `management.health.<name>.enabled` kill-switch now disables the
  matching Pulse contributor (default `true`). Applies to `mount`, `mule`, `oauth2`,
  and `pulseCustom`. Comes in addition to `pulse.<module>.enabled` — setting either
  to `false` keeps the contributor out. Implemented by adding
  `@ConditionalOnEnabledHealthIndicator` to each module's auto-config and to the
  `pulseCustom` bean, matching the convention Boot's own indicators follow
  (`management.health.db.enabled`, `management.health.redis.enabled`, etc.).
- JaCoCo coverage measurement. The `jacoco-maven-plugin` merges Surefire (unit + slice)
  and Failsafe (IT) execution data into a combined report at `target/site/jacoco/`.
  The CI workflow uploads the HTML report as an artefact on every run and pushes
  `jacoco.xml` to Codecov. README displays a coverage badge alongside the CI badge.
- Dependabot configuration (`.github/dependabot.yml`). Weekly Monday scans of Maven
  and GitHub Actions dependencies, with grouped PRs for the Spring Boot, Testcontainers,
  and Jackson families so monthly Boot patches don't fan out into many small PRs.

### Dependency upgrades
- `org.testcontainers:testcontainers-bom` 1.21.3 → 2.0.5 (major bump — see note below)
- `com.github.dasniko:testcontainers-keycloak` 3.6.0 → 4.2.1
- `org.wiremock:wiremock-standalone` 3.13.1 → 3.13.2
- `actions/checkout` 4 → 6
- `actions/setup-java` 4 → 5
- `actions/upload-artifact` 4 → 7
- `codecov/codecov-action` 5 → 6

> **Testcontainers 2.x note**: the JUnit Jupiter extension artifact was renamed from
> `org.testcontainers:junit-jupiter` to `org.testcontainers:testcontainers-junit-jupiter`
> (aligning with the other 2.x module names). Pulse only consumes this dependency in
> test scope, so consumers of `pulse-starter` are unaffected.

## [0.2.0] — 2026-05-15

Hardening release. Two material features, both aimed at running Pulse in Kubernetes.

### Added
- **`pulse.check-timeout`** (default `5s`). Outer deadline applied to every check by
  `PulseCheckAdapter`. A hung `PulseCheck.check()` — degraded NFS mount, stuck socket,
  deadlocked custom check — now reports `DOWN` with `details.error="check timed out after PT5S"`
  instead of blocking the entire `/actuator/health` response. Without this guard a
  single stuck check could trip K8s liveness probes and trigger a needless pod restart.
- **`pulse.<module>.probes`** per-module property routing each Pulse contributor into
  K8s liveness and/or readiness probe groups. All modules default to `[readiness]` so
  downstream failures drop the pod from the load balancer without restarting it; opt
  into `liveness` per-module for genuinely pod-fatal conditions. Wiring is handled by
  `PulseHealthGroupsEnvironmentPostProcessor`, which merges contributor names into
  `management.endpoint.health.group.<probe>.include` while preserving consumer-set
  values and Spring Boot's `livenessState` / `readinessState` defaults.
- CI workflow (`.github/workflows/ci.yml`) running `./mvnw verify` on every push to
  `main` and every PR. Concurrency cancellation, failure-report artefact upload, and
  a status badge in the README.

### Compatibility
- Drop-in for 0.1.0 consumers — no breaking changes. New properties default to safe
  values (`pulse.check-timeout=5s`, `pulse.<module>.probes=[readiness]`). Set
  `probes: []` per-module to restore 0.1.0 behaviour where Pulse contributors only
  appeared under `/actuator/health` overall.

## [0.1.0] — 2026-05-14

First release. A Spring Boot 4 starter that ships actuator health indicators for the
gaps Spring Boot doesn't cover out of the box, plus an SPI for consumer-defined checks
with uniform `latencyMs` / `lastSuccessAt` / `lastFailureAt` decoration.

### Added
- **Mount-point check** (`pulse.mount`). Verifies a configured path exists, is a
  directory, is readable, has free space above optional `min-free-bytes` /
  `min-free-percent` thresholds, and is actually mounted (catches `getTotalSpace() == 0`
  from degraded SMB/FUSE mounts).
- **Mule HTTP check** (`pulse.mule`). Pings a configurable list of HTTP endpoints and
  asserts the expected status code. JDK `HttpClient`, connect-timeout sized to half
  the per-request budget, http/https scheme allowlist.
- **OAuth2 `client_credentials` check** (`pulse.oauth2`). Performs a real handshake
  against the IdP using credentials resolved from the existing
  `spring-security-oauth2-client` configuration (no duplication into `pulse.*`). Token
  cache refreshes at 80% of `min(token.expires_in, cache-ttl)`. RFC 6749 §2.3.1-compliant
  Basic auth encoding (URL-encodes both halves before base64). Defensive against buggy
  IdPs: `expires_in <= 0` falls back to `cache-ttl`; raw error bodies are never echoed
  into health details (which could leak reflected `Authorization` headers); token value
  itself is never included in details.
- **`PulseCheck` SPI** (`dev.kc.pulse.core.PulseCheck`). Interface for consumer-defined
  checks; auto-discovered as Spring beans and surfaced under `pulseCustom.<name>`.
- Maven Publish workflow (`.github/workflows/maven-publish.yml`) that publishes to
  GitHub Packages on Release creation.

### Test coverage
- 39 unit + auto-configuration slice tests
- 8 integration tests (Keycloak Testcontainer for OAuth2, WireMock for Mule HTTP)

[Unreleased]: https://github.com/kcsurapaneni/pulse/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/kcsurapaneni/pulse/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/kcsurapaneni/pulse/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/kcsurapaneni/pulse/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/kcsurapaneni/pulse/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/kcsurapaneni/pulse/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/kcsurapaneni/pulse/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/kcsurapaneni/pulse/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/kcsurapaneni/pulse/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/kcsurapaneni/pulse/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kcsurapaneni/pulse/releases/tag/v0.1.0
