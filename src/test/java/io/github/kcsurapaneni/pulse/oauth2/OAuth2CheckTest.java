package io.github.kcsurapaneni.pulse.oauth2;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.CheckMode;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.OnTransientFailure;
import io.github.kcsurapaneni.pulse.oauth2.OAuth2Properties.Provider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class OAuth2CheckTest {

    private HttpServer server;
    private HttpClient httpClient;
    private int port;
    private ObjectMapper objectMapper;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upOnValidTokenResponse() {
        server.createContext("/token", tokenResponder(new AtomicInteger()));
        server.start();

        Health health = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("cached", false)
                .containsEntry("tokenType", "Bearer")
                .containsEntry("expiresInSec", 3600)
                .containsEntry("httpStatus", 200)
                .containsEntry("authMethod", "client_secret_post");
    }

    @Test
    void usesBasicAuthWhenRegistrationConfiguresIt() {
        AtomicInteger basicCalls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
                basicCalls.incrementAndGet();
            }
            writeResponse(exchange, 200, validTokenBody());
        });
        server.start();

        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId("client").clientSecret("secret")
                .tokenUri(url("/token"))
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .build();

        Health health = newCheck(new InMemoryClientRegistrationRepository(reg), Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("authMethod", "client_secret_basic");
        assertThat(basicCalls.get()).isEqualTo(1);
    }

    @Test
    void basicAuthEncodesCredentialsPerRfc6749() {
        String complexId = "my:client+id";
        String complexSecret = "p@ss/word:with=specials";
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        server.createContext("/token", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeResponse(exchange, 200, validTokenBody());
        });
        server.start();

        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId(complexId).clientSecret(complexSecret)
                .tokenUri(url("/token"))
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .build();

        Health health = newCheck(new InMemoryClientRegistrationRepository(reg), Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);

        // Decode the Authorization header per RFC 6749 §2.3.1:
        // base64-decode → split on FIRST ':' → URL-decode each half.
        String authHeader = receivedAuth.get();
        assertThat(authHeader).startsWith("Basic ");
        String decoded = new String(
                Base64.getDecoder().decode(authHeader.substring("Basic ".length())),
                StandardCharsets.US_ASCII);
        int colon = decoded.indexOf(':');
        assertThat(colon).isGreaterThan(0);
        String decodedId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
        String decodedSecret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        assertThat(decodedId).isEqualTo(complexId);
        assertThat(decodedSecret).isEqualTo(complexSecret);
    }

    @Test
    void downWhenClientSecretIsBlank() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId("client").clientSecret("")
                .tokenUri("https://example/token")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();

        Health health = newCheck(new InMemoryClientRegistrationRepository(reg), Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("error", "ClientRegistration has no client-secret");
    }

    @Test
    void nonJsonErrorBodyIsNotEchoed() {
        // A misbehaving IdP could reflect headers (incl. Authorization) back; we must not surface
        // the raw body under details.error.
        String sensitiveReflection = "Authorization: Basic dXNlcjpzZWNyZXQ=  ...lots more...";
        server.createContext("/token", respond(401, sensitiveReflection));
        server.start();

        Health health = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        String error = (String) health.getDetails().get("error");
        assertThat(error).doesNotContain("Basic dXNlcjpzZWNyZXQ=");
        assertThat(error).contains("non-JSON response body");
    }

    @Test
    void fallsBackToCacheTtlWhenExpiresInIsZero() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            writeResponse(exchange, 200,
                    "{\"access_token\":\"abc\",\"token_type\":\"Bearer\",\"expires_in\":0}");
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                clock);

        Health first = check.check();
        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(first.getDetails()).containsKey("warn");
        // cache should hold for ~80% of cache-ttl (5m default) → ~4m
        clock.advance(Duration.ofMinutes(3));
        Health second = check.check();
        assertThat(second.getDetails()).containsEntry("cached", true);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void downWhenRegistrationMissing() {
        InMemoryClientRegistrationRepository empty = new InMemoryClientRegistrationRepository(
                registration("other", url("/token"), "client", "secret"));

        Health health = newCheck(empty, Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .startsWith("no ClientRegistration found for id");
    }

    @Test
    void downOn401WithErrorDescription() {
        server.createContext("/token", respond(401,
                "{\"error\":\"invalid_client\",\"error_description\":\"bad credentials\"}"));
        server.start();

        Health health = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("httpStatus", 401);
        assertThat((String) health.getDetails().get("error"))
                .contains("invalid_client")
                .contains("bad credentials");
    }

    @Test
    void downOnUnparseableSuccessBody() {
        server.createContext("/token", respond(200, "not json"));
        server.start();

        Health health = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .startsWith("failed to parse token response");
    }

    @Test
    void downWhenConnectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        ClientRegistration reg = registration("test", "http://127.0.0.1:" + closedPort + "/token",
                "client", "secret");

        Health health = newCheck(repoWith(reg), Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void cacheHitAvoidsServerCall() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", tokenResponder(calls));
        server.start();

        OAuth2Check check = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC());
        Health first = check.check();
        Health second = check.check();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(first.getDetails()).containsEntry("cached", false);
        assertThat(second.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getDetails()).containsEntry("cached", true);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cacheRefreshesAfterRefreshAt() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", tokenResponder(calls));
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheck(repoWith(registration("test", url("/token"), "client", "secret")),
                clock);

        check.check();
        clock.advance(Duration.ofMinutes(3));
        check.check();
        clock.advance(Duration.ofMinutes(2));
        check.check();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void picksUpRotatedSecretOnNextRefresh() {
        AtomicInteger acceptedRequests = new AtomicInteger();
        server.createContext("/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("client_secret=new-secret")) {
                acceptedRequests.incrementAndGet();
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 401, "{\"error\":\"invalid_client\"}");
            }
        });
        server.start();

        MutableClientRegistrationRepository repo = new MutableClientRegistrationRepository(
                registration("test", url("/token"), "client", "old-secret"));

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheck(repo, clock);

        Health first = check.check();
        assertThat(first.getStatus()).isEqualTo(Status.DOWN);

        repo.set(registration("test", url("/token"), "client", "new-secret"));
        clock.advance(Duration.ofMinutes(5));

        Health second = check.check();
        assertThat(second.getStatus()).isEqualTo(Status.UP);
        assertThat(acceptedRequests.get()).isEqualTo(1);
    }

    // ---------- on-transient-failure: stale ----------

    @Test
    void staleModeReturnsUpWhenIdpThrowsIoException() throws IOException {
        // Phase 1: handshake succeeds, cache a token with 1h natural lifetime
        AtomicInteger phase = new AtomicInteger(0);
        server.createContext("/token", exchange -> {
            if (phase.get() == 0) {
                writeResponse(exchange, 200, validTokenBody());
            }
            // Phase 1 success only — we'll stop the server entirely to simulate the outage.
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        Health first = check.check();
        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(first.getDetails()).containsEntry("cached", false);

        // Phase 2: stop the server entirely → IOException on next refresh.
        server.stop(0);
        server = null; // avoid double-stop in @AfterEach
        clock.advance(Duration.ofMinutes(5)); // past refresh point but well before natural expiry

        Health second = check.check();

        assertThat(second.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getDetails())
                .containsEntry("stale", true)
                .containsEntry("cached", true)
                .containsEntry("tokenType", "Bearer");
        assertThat((String) second.getDetails().get("staleReason"))
                .containsAnyOf("Exception", "Error");
    }

    @Test
    void staleModeReturnsUpOn503() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 503, "{}");
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();
        clock.advance(Duration.ofMinutes(5));
        Health stale = check.check();

        assertThat(stale.getStatus()).isEqualTo(Status.UP);
        assertThat(stale.getDetails())
                .containsEntry("stale", true)
                .containsEntry("httpStatus", 503);
        assertThat(stale.getDetails().get("staleReason")).isNotNull();
    }

    @Test
    void staleModeReturnsUpOn429() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 429, "{\"error\":\"rate_limited\"}");
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();
        clock.advance(Duration.ofMinutes(5));
        Health stale = check.check();

        assertThat(stale.getStatus()).isEqualTo(Status.UP);
        assertThat(stale.getDetails())
                .containsEntry("stale", true)
                .containsEntry("httpStatus", 429);
        assertThat((String) stale.getDetails().get("staleReason")).contains("rate_limited");
    }

    @Test
    void staleModeStillReportsDownOn401() {
        // 4xx (not 429) means credentials are bad — we must NOT keep returning a stale token,
        // otherwise rotated/revoked credentials would silently keep reporting UP.
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 401, "{\"error\":\"invalid_client\"}");
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();
        clock.advance(Duration.ofMinutes(5));
        Health down = check.check();

        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).doesNotContainKey("stale");
        assertThat(down.getDetails()).containsEntry("httpStatus", 401);
    }

    @Test
    void staleModeFallsBackToDownWhenCacheEmpty() {
        // No previous successful handshake → no token to be stale about.
        server.createContext("/token", respond(503, "{}"));
        server.start();

        Health down = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")),
                Clock.systemUTC()).check();

        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).doesNotContainKey("stale");
    }

    @Test
    void staleModeReturnsDownAfterTokenPastNaturalExpiry() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 503, "{}");
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();
        // Token reports expires_in=3600 (validTokenBody) → advance past it.
        clock.advance(Duration.ofHours(2));

        Health expired = check.check();
        assertThat(expired.getStatus()).isEqualTo(Status.DOWN);
        assertThat(expired.getDetails()).doesNotContainKey("stale");
    }

    @Test
    void defaultDownModePreserved() {
        // Same scenario as staleModeReturnsUpOn503 but using the default (DOWN). Catches any
        // accidental behaviour change for the existing-consumer path.
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                writeResponse(exchange, 200, validTokenBody());
            }
            else {
                writeResponse(exchange, 503, "{}");
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheck(
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();
        clock.advance(Duration.ofMinutes(5));
        Health down = check.check();

        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).doesNotContainKey("stale");
        assertThat(down.getDetails()).containsEntry("httpStatus", 503);
    }

    @Test
    void recoveringIdpDropsStaleFlagOnNextProbe() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 2) {
                writeResponse(exchange, 503, "{}");
            }
            else {
                writeResponse(exchange, 200, validTokenBody());
            }
        });
        server.start();

        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OAuth2Check check = newCheckWithProvider(staleProvider(),
                repoWith(registration("test", url("/token"), "client", "secret")), clock);

        check.check();                       // call #1: 200 → fresh UP
        clock.advance(Duration.ofMinutes(5));
        Health stale = check.check();        // call #2: 503 → stale UP
        clock.advance(Duration.ofSeconds(1));
        Health recovered = check.check();    // call #3: 200 → fresh UP again

        assertThat(stale.getDetails()).containsEntry("stale", true);
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        assertThat(recovered.getDetails()).doesNotContainKey("stale");
        assertThat(recovered.getDetails()).containsEntry("cached", false);
    }

    private Provider staleProvider() {
        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        p.setOnTransientFailure(OnTransientFailure.STALE);
        return p;
    }

    // ---------- REACHABLE mode ----------

    @Test
    void reachableUpOnDiscoveryDoc200() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            calls.incrementAndGet();
            writeResponse(exchange, 200, "{\"issuer\":\"http://127.0.0.1\"}");
        });
        server.start();

        Provider p = reachableProviderWithDiscoveryUri(url("/.well-known/openid-configuration"));
        Health health = newCheckWithProvider(p,
                repoWith(registrationWithoutSecret("test", url("/token"))),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("mode", "reachable")
                .containsEntry("httpStatus", 200)
                .containsEntry("discoveryUri", url("/.well-known/openid-configuration"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void reachableDownOnDiscoveryDoc500() {
        server.createContext("/.well-known/openid-configuration", respond(503, ""));
        server.start();

        Provider p = reachableProviderWithDiscoveryUri(url("/.well-known/openid-configuration"));
        Health health = newCheckWithProvider(p,
                repoWith(registrationWithoutSecret("test", url("/token"))),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("httpStatus", 503);
        assertThat((String) health.getDetails().get("error")).contains("503");
    }

    @Test
    void reachableDownOn404IncludesHint() {
        server.createContext("/.well-known/openid-configuration", respond(404, ""));
        server.start();

        Provider p = reachableProviderWithDiscoveryUri(url("/.well-known/openid-configuration"));
        Health health = newCheckWithProvider(p,
                repoWith(registrationWithoutSecret("test", url("/token"))),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .contains("404")
                .contains("issuer-uri / discovery-uri");
    }

    @Test
    void reachableDownWhenNeitherDiscoveryUriNorIssuerUriSet() {
        // No discoveryUri on Provider, and the registration has only a token-uri (no issuer-uri).
        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        p.setMode(CheckMode.REACHABLE);

        Health health = newCheckWithProvider(p,
                repoWith(registrationWithoutSecret("test", url("/token"))),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .contains("reachable mode requires")
                .contains("discovery-uri")
                .contains("issuer-uri");
        // The mode should still surface so operators can see what we were trying to do.
        assertThat(health.getDetails()).containsEntry("mode", "reachable");
    }

    @Test
    void reachableUsesExplicitDiscoveryUriOverIssuer() {
        // Server only serves /alt. If the check derived from a (non-existent) issuer-uri we'd 404.
        AtomicInteger altHits = new AtomicInteger();
        server.createContext("/alt", exchange -> {
            altHits.incrementAndGet();
            writeResponse(exchange, 200, "{}");
        });
        server.start();

        Provider p = reachableProviderWithDiscoveryUri(url("/alt"));
        // Issuer URI present but pointing elsewhere — explicit override should win.
        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId("client").clientSecret("secret")
                .tokenUri("https://elsewhere.example/token")
                .issuerUri("https://elsewhere.example/issuer")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();

        Health health = newCheckWithProvider(p, new InMemoryClientRegistrationRepository(reg),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("discoveryUri", url("/alt"));
        assertThat(altHits.get()).isEqualTo(1);
    }

    @Test
    void reachableDerivesDiscoveryUriFromIssuerWithTrailingSlash() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/realm/.well-known/openid-configuration", exchange -> {
            hits.incrementAndGet();
            writeResponse(exchange, 200, "{}");
        });
        server.start();

        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        p.setMode(CheckMode.REACHABLE);

        // Trailing slash on issuer-uri — check we strip it (don't produce '//.well-known/...').
        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId("client").clientSecret("secret")
                .tokenUri(url("/token"))
                .issuerUri(url("/realm") + "/")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();

        Health health = newCheckWithProvider(p, new InMemoryClientRegistrationRepository(reg),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("discoveryUri",
                url("/realm/.well-known/openid-configuration"));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void reachableDoesNotRequireClientSecret() {
        // Reachable mode must work when the registration has no client-secret — that's the whole
        // point of the mode (cheap network probe without exercising credentials).
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            hits.incrementAndGet();
            writeResponse(exchange, 200, "{}");
        });
        server.start();

        Provider p = reachableProviderWithDiscoveryUri(url("/.well-known/openid-configuration"));
        ClientRegistration reg = ClientRegistration.withRegistrationId("test")
                .clientId("client").clientSecret("placeholder")
                .tokenUri(url("/token"))
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();

        Health health = newCheckWithProvider(p, new InMemoryClientRegistrationRepository(reg),
                Clock.systemUTC()).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(hits.get()).isEqualTo(1);
        // Reachable mode must not leak the clientId into details — only handshake mode does that.
        assertThat(health.getDetails()).doesNotContainKey("clientId");
    }

    private OAuth2Check newCheck(ClientRegistrationRepository repo, Clock clock) {
        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        return new OAuth2Check(p, repo, httpClient, Duration.ofSeconds(2), clock, objectMapper);
    }

    private OAuth2Check newCheckWithProvider(Provider p, ClientRegistrationRepository repo, Clock clock) {
        return new OAuth2Check(p, repo, httpClient, Duration.ofSeconds(2), clock, objectMapper);
    }

    private Provider reachableProviderWithDiscoveryUri(String discoveryUri) {
        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        p.setMode(CheckMode.REACHABLE);
        p.setDiscoveryUri(discoveryUri);
        return p;
    }

    private static ClientRegistration registrationWithoutSecret(String id, String tokenUri) {
        // Spring Security requires a non-blank client-secret on a CLIENT_SECRET_POST registration
        // (validated by ClientRegistration.Builder), so we use a placeholder. The reachable path
        // doesn't read the secret, which is what these tests are verifying.
        return ClientRegistration.withRegistrationId(id)
                .clientId("client")
                .clientSecret("placeholder")
                .tokenUri(tokenUri)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();
    }

    private static ClientRegistration registration(String id, String tokenUri,
            String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tokenUri(tokenUri)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();
    }

    private static InMemoryClientRegistrationRepository repoWith(ClientRegistration reg) {
        return new InMemoryClientRegistrationRepository(reg);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static String validTokenBody() {
        return "{\"access_token\":\"abc\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    }

    private static HttpHandler tokenResponder(AtomicInteger calls) {
        return exchange -> {
            calls.incrementAndGet();
            writeResponse(exchange, 200, validTokenBody());
        };
    }

    private static HttpHandler respond(int status, String body) {
        return exchange -> writeResponse(exchange, status, body);
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static final class MutableClientRegistrationRepository implements ClientRegistrationRepository {

        private volatile ClientRegistration current;

        MutableClientRegistrationRepository(ClientRegistration initial) {
            this.current = initial;
        }

        void set(ClientRegistration next) {
            this.current = next;
        }

        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            return registrationId.equals(current.getRegistrationId()) ? current : null;
        }
    }

    private static final class AdvanceableClock extends Clock {

        private Instant now;

        AdvanceableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
