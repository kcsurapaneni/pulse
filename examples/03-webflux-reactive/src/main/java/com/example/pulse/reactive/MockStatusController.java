package com.example.pulse.reactive;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Tiny in-process responder used by {@link DownstreamReactiveCheck}. Replaces the previous
 * {@code httpbin.org} dependency so the example is self-contained — clone, run, no external
 * network needed.
 *
 * <p>{@code GET /mock/status/{code}} returns the requested status with an empty body. Returns a
 * {@link Mono} so the handler stays fully on the reactive scheduler.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@RestController
class MockStatusController {

    @GetMapping("/mock/status/{code}")
    Mono<ResponseEntity<Void>> status(@PathVariable int code) {
        return Mono.just(ResponseEntity.status(code).build());
    }
}
