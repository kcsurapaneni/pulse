package dev.kc.pulse.oauth2;

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

import dev.kc.pulse.oauth2.OAuth2Properties.Provider;
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

    private OAuth2Check newCheck(ClientRegistrationRepository repo, Clock clock) {
        Provider p = new Provider();
        p.setName("test");
        p.setRegistrationId("test");
        return new OAuth2Check(p, repo, httpClient, Duration.ofSeconds(2), clock, objectMapper);
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
