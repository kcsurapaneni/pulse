package io.github.kcsurapaneni.pulse.oauth2;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.kcsurapaneni.pulse.core.PulseCheck;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.CheckMode;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.Provider;

import org.springframework.boot.health.contributor.Health;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public class OAuth2Check implements PulseCheck {

    private final Provider config;
    private final ClientRegistrationRepository registrations;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OAuth2TokenCache cache = new OAuth2TokenCache();

    public OAuth2Check(Provider config, ClientRegistrationRepository registrations,
            HttpClient httpClient, Duration timeout, Clock clock, ObjectMapper objectMapper) {
        this.config = config;
        this.registrations = registrations;
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public Health check() {
        CheckMode mode = config.getMode() != null ? config.getMode() : CheckMode.HANDSHAKE;
        Health.Builder b = new Health.Builder()
                .withDetail("registrationId", config.getRegistrationId())
                .withDetail("mode", mode.name().toLowerCase());

        ClientRegistration registration = registrations.findByRegistrationId(config.getRegistrationId());
        if (registration == null) {
            return b.down()
                    .withDetail("error", "no ClientRegistration found for id: " + config.getRegistrationId())
                    .build();
        }

        if (mode == CheckMode.REACHABLE) {
            return performReachabilityCheck(b, registration);
        }
        return performHandshakeMode(b, registration);
    }

    private Health performHandshakeMode(Health.Builder b, ClientRegistration registration) {
        String tokenUri = registration.getProviderDetails() != null
                ? registration.getProviderDetails().getTokenUri()
                : null;
        if (tokenUri == null || tokenUri.isBlank()) {
            return b.down().withDetail("error", "ClientRegistration has no token-uri").build();
        }
        if (registration.getClientId() == null || registration.getClientId().isBlank()) {
            return b.down().withDetail("error", "ClientRegistration has no client-id").build();
        }
        if (registration.getClientSecret() == null || registration.getClientSecret().isBlank()) {
            return b.down().withDetail("error", "ClientRegistration has no client-secret").build();
        }

        b.withDetail("tokenUri", tokenUri).withDetail("clientId", registration.getClientId());

        Instant now = clock.instant();
        if (cache.isFresh(now)) {
            OAuth2TokenCache.Snapshot snap = cache.snapshot();
            return b.up()
                    .withDetail("cached", true)
                    .withDetail("tokenType", snap.tokenType())
                    .withDetail("expiresInSec", remainingSeconds(snap, now))
                    .build();
        }
        return performHandshake(b, registration, tokenUri, now);
    }

    private Health performReachabilityCheck(Health.Builder b, ClientRegistration registration) {
        String discoveryUri = resolveDiscoveryUri(registration);
        if (discoveryUri == null) {
            return b.down()
                    .withDetail("error", "reachable mode requires either "
                            + "pulse.oauth2.providers[].discovery-uri or "
                            + "spring.security.oauth2.client.provider.<id>.issuer-uri to be set")
                    .build();
        }
        b.withDetail("discoveryUri", discoveryUri);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(discoveryUri))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        }
        catch (IllegalArgumentException ex) {
            return b.down().withDetail("error", "invalid discovery-uri: " + ex.getMessage()).build();
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, BodyHandlers.discarding());
            b.withDetail("httpStatus", response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return b.up().build();
            }
            String error = "discovery endpoint returned " + response.statusCode();
            if (response.statusCode() == 404) {
                error += " (check issuer-uri / discovery-uri)";
            }
            return b.down().withDetail("error", error).build();
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

    private String resolveDiscoveryUri(ClientRegistration registration) {
        if (config.getDiscoveryUri() != null && !config.getDiscoveryUri().isBlank()) {
            return config.getDiscoveryUri();
        }
        String issuerUri = registration.getProviderDetails() != null
                ? registration.getProviderDetails().getIssuerUri()
                : null;
        if (issuerUri == null || issuerUri.isBlank()) {
            return null;
        }
        String trimmed = issuerUri.endsWith("/")
                ? issuerUri.substring(0, issuerUri.length() - 1)
                : issuerUri;
        return trimmed + "/.well-known/openid-configuration";
    }

    private Health performHandshake(Health.Builder b, ClientRegistration registration,
            String tokenUri, Instant now) {
        ClientAuthenticationMethod authMethod = registration.getClientAuthenticationMethod();
        boolean useBasic = ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(authMethod);

        HttpRequest request;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json");
            if (useBasic) {
                rb.header("Authorization", buildBasicAuthHeader(
                        registration.getClientId(), registration.getClientSecret()));
            }
            request = rb.POST(BodyPublishers.ofString(buildFormBody(registration, !useBasic),
                    StandardCharsets.UTF_8)).build();
        }
        catch (IllegalArgumentException ex) {
            return b.down().withDetail("error", "invalid token-uri: " + ex.getMessage()).build();
        }

        b.withDetail("authMethod", useBasic ? "client_secret_basic" : "client_secret_post");

        try {
            HttpResponse<String> response = httpClient.send(request,
                    BodyHandlers.ofString(StandardCharsets.UTF_8));
            b.withDetail("httpStatus", response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return processSuccess(b, response, now);
            }
            cache.clear();
            return b.down()
                    .withDetail("error",
                            "client_credentials handshake failed: " + extractError(response.body()))
                    .build();
        }
        catch (IOException ex) {
            cache.clear();
            return b.down()
                    .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cache.clear();
            return b.down().withDetail("error", "interrupted").build();
        }
    }

    private Health processSuccess(Health.Builder b, HttpResponse<String> response, Instant now) {
        try {
            JsonNode node = objectMapper.readTree(response.body());
            String tokenType = node.path("token_type").asText("Bearer");
            int reportedExpiresIn = node.path("expires_in").asInt(0);
            int effectiveExpiresIn = reportedExpiresIn;
            if (reportedExpiresIn <= 0) {
                // A buggy IdP returning 0 or negative would otherwise force the cache to refresh
                // every ~0.8s — fall back to cache-ttl so we still cache something sensible.
                effectiveExpiresIn = (int) Math.min(Integer.MAX_VALUE, config.getCacheTtl().getSeconds());
                b.withDetail("warn", "server returned non-positive expires_in; falling back to cache-ttl");
            }
            cache.store(now, tokenType, effectiveExpiresIn, config.getCacheTtl());
            return b.up()
                    .withDetail("cached", false)
                    .withDetail("tokenType", tokenType)
                    .withDetail("expiresInSec", effectiveExpiresIn)
                    .build();
        }
        catch (IOException ex) {
            cache.clear();
            return b.down()
                    .withDetail("error", "failed to parse token response: " + ex.getMessage())
                    .build();
        }
    }

    /**
     * Build the {@code Authorization: Basic ...} header per RFC 6749 §2.3.1 — both halves are
     * form-urlencoded before being joined and base64-encoded. Required for credentials containing
     * {@code :}, {@code @}, {@code +}, {@code /}, or non-ASCII bytes.
     */
    private static String buildBasicAuthHeader(String clientId, String clientSecret) {
        String encodedId = URLEncoder.encode(clientId, StandardCharsets.UTF_8);
        String encodedSecret = URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        String creds = encodedId + ":" + encodedSecret;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.US_ASCII));
    }

    private String buildFormBody(ClientRegistration registration, boolean includeCredentials) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credentials");
        if (includeCredentials) {
            params.put("client_id", registration.getClientId());
            params.put("client_secret", registration.getClientSecret());
        }
        if (registration.getScopes() != null && !registration.getScopes().isEmpty()) {
            params.put("scope", String.join(" ", registration.getScopes()));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        // Only surface well-known OAuth2 error fields. Never echo the raw body — a misbehaving
        // IdP may reflect request headers (including the Authorization header) back into the
        // response, and we don't want that landing in /actuator/health.
        try {
            JsonNode node = objectMapper.readTree(body);
            String err = node.path("error").asText("");
            String desc = node.path("error_description").asText("");
            if (!err.isBlank() && !desc.isBlank()) {
                return err + " - " + desc;
            }
            if (!err.isBlank()) {
                return err;
            }
            return "JSON response without an `error` field";
        }
        catch (IOException ex) {
            return "non-JSON response body";
        }
    }

    private long remainingSeconds(OAuth2TokenCache.Snapshot snap, Instant now) {
        Instant expiresAt = snap.cachedAt().plusSeconds(snap.expiresInSec());
        return Math.max(0L, Duration.between(now, expiresAt).getSeconds());
    }
}
