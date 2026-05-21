package io.github.kcsurapaneni.pulse.mule;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;

import io.github.kcsurapaneni.pulse.mule.MuleProperties.Service;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class MuleCheckTest {

    private HttpServer server;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upWhenStatusMatches() {
        register("/health", respondWith(200));
        server.start();

        Health health = checkOf("svc", url("/health"), 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("httpStatus", 200)
                .containsEntry("expectedStatus", 200);
    }

    @Test
    void downWhenStatusDiffers() {
        register("/health", respondWith(503));
        server.start();

        Health health = checkOf("svc", url("/health"), 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("httpStatus", 503)
                .containsEntry("error", "unexpected status code");
    }

    @Test
    void downWhenConnectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        Health health = checkOf("svc", "http://127.0.0.1:" + closedPort + "/health", 200,
                Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsKey("error")
                .containsEntry("url", "http://127.0.0.1:" + closedPort + "/health");
    }

    @Test
    void downWhenRequestTimesOut() {
        register("/slow", exchange -> {
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        Health health = checkOf("svc", url("/slow"), 200, Duration.ofMillis(100)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void downWhenSchemeIsNotHttp() {
        Health health = checkOf("svc", "file:///etc/passwd", 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error"))
                .startsWith("unsupported URL scheme: file");
    }

    @Test
    void downWhenUrlIsInvalid() {
        Health health = checkOf("svc", "not a url", 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(((String) health.getDetails().get("error"))).startsWith("invalid url");
    }

    private MuleCheck checkOf(String name, String url, int expected, Duration timeout) {
        Service svc = new Service();
        svc.setName(name);
        svc.setUrl(url);
        svc.setExpectedStatus(expected);
        return new MuleCheck(svc, httpClient, timeout);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private void register(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static HttpHandler respondWith(int status) {
        return exchange -> {
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(new byte[0]);
            }
        };
    }
}
