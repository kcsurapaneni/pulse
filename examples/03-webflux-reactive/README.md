# Example 03 — WebFlux + `ReactivePulseCheck`

A non-blocking Spring Boot WebFlux app with one `ReactivePulseCheck` bean. The check pings a local `MockStatusController` via `WebClient`, runs fully on the reactive scheduler, and surfaces under `/actuator/health/pulseReactive/downstream`. In a real deployment you'd point the `downstreamClient` bean at whatever HTTP service you actually want to verify.

## What it shows

- How to implement the `ReactivePulseCheck` SPI returning `Mono<Health>` (see `DownstreamReactiveCheck.java`)
- That the `pulseReactive` composite is auto-registered when `reactor-core` is on the classpath — no extra config needed
- Same decoration (`latencyMs`, `lastSuccessAt`, `lastFailureAt`) and same outer deadline (`pulse.check-timeout`, via `Mono.timeout`) as the blocking SPI
- That the reactive composite gets its own K8s probe-routing property (`pulse.reactive.probes`), independent from `pulse.custom.probes`

## Prerequisites

- Java 21+, Maven 3.9+
- Internet access only at first build (Maven resolves `pulse-starter` from Maven Central). The example itself needs no external network — the reactive check targets an in-process `MockStatusController`.

## Run

```bash
cd examples/03-webflux-reactive
mvn spring-boot:run
```

Then:

```bash
curl -s http://localhost:8080/actuator/health | jq
```

Expected (truncated):

```json
{
  "status": "UP",
  "components": {
    "pulseReactive": {
      "status": "UP",
      "components": {
        "downstream": {
          "status": "UP",
          "details": {
            "httpStatus": 200,
            "latencyMs": 142,
            "lastSuccessAt": "2026-05-21T..."
          }
        }
      }
    },
    "reactivePing": { "status": "UP" }
  }
}
```

## Try a failure

Edit `DownstreamReactiveCheck.java` to hit `/mock/status/500` instead of `/mock/status/200`:

```java
.uri("/mock/status/500")
```

The `retrieve().toBodilessEntity()` chain converts non-2xx into an error, which the `onErrorResume` branch maps to `Health.down()`:

```json
"downstream": {
  "status": "DOWN",
  "details": {
    "error": "WebClientResponseException$InternalServerError: ...",
    "latencyMs": 138,
    "lastFailureAt": "2026-05-21T..."
  }
}
```

## When to use reactive checks

Choose `ReactivePulseCheck` over `PulseCheck` when:

- Your app is on WebFlux and your check uses reactive clients (`WebClient`, R2DBC, etc.). Returning a `Mono` lets the check participate fully in the non-blocking pipeline.
- The check's work is meaningful in size — chained calls, conditional follow-ups, parallel fan-out via `Mono.zip`. The reactive form is easier to write than the equivalent blocking code.

Stick with the blocking `PulseCheck` when:

- The work is genuinely synchronous (filesystem stat, in-memory state). There's nothing to gain from reactive wrapping.
- Your app is MVC, not WebFlux.

In a WebFlux app, blocking `PulseCheck` beans still work — Spring Boot wraps them onto `Schedulers.boundedElastic()` automatically. The reactive SPI avoids that wrap.

## See also

- Example 01: blocking `PulseCheck` SPI
- Example 02: built-in mount + mule checks with K8s probe routing
