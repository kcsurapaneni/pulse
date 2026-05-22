package com.example.pulse.all;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny in-process responder used by the example's Mule check entries. Replaces the previous
 * {@code httpbin.org} dependency so the example is self-contained — clone, run, no external
 * network needed.
 *
 * <p>{@code GET /mock/status/{code}} returns the requested status with an empty body. The two
 * Mule services configured in {@code application.yml} both target this endpoint.
 *
 * @author Krishna Chaitanya Surapaneni
 */
@RestController
class MockStatusController {

    @GetMapping("/mock/status/{code}")
    ResponseEntity<Void> status(@PathVariable int code) {
        return ResponseEntity.status(code).build();
    }
}
