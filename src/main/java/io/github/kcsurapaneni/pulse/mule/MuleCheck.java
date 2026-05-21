package io.github.kcsurapaneni.pulse.mule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import io.github.kcsurapaneni.pulse.core.PulseCheck;
import io.github.kcsurapaneni.pulse.mule.MuleProperties.Service;

import org.springframework.boot.health.contributor.Health;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public class MuleCheck implements PulseCheck {

    private final Service config;
    private final HttpClient httpClient;
    private final Duration timeout;

    public MuleCheck(Service config, HttpClient httpClient, Duration timeout) {
        this.config = config;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public Health check() {
        Health.Builder b = new Health.Builder()
                .withDetail("url", config.getUrl())
                .withDetail("expectedStatus", config.getExpectedStatus());

        URI uri;
        try {
            uri = URI.create(config.getUrl());
        }
        catch (IllegalArgumentException ex) {
            return b.down()
                    .withDetail("error", "invalid url: " + ex.getMessage())
                    .build();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return b.down()
                    .withDetail("error", "unsupported URL scheme: " + scheme + " (must be http or https)")
                    .build();
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .GET()
                    .build();
        }
        catch (IllegalArgumentException ex) {
            return b.down()
                    .withDetail("error", "invalid url: " + ex.getMessage())
                    .build();
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, BodyHandlers.discarding());
            b.withDetail("httpStatus", response.statusCode());
            if (response.statusCode() == config.getExpectedStatus()) {
                return b.up().build();
            }
            return b.down().withDetail("error", "unexpected status code").build();
        }
        catch (IOException ex) {
            return b.down()
                    .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return b.down().withDetail("error", "interrupted").build();
        }
    }
}
