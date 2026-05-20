# Changelog

All notable changes to Pulse are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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

[Unreleased]: https://github.com/kcsurapaneni/pulse/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/kcsurapaneni/pulse/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kcsurapaneni/pulse/releases/tag/v0.1.0
