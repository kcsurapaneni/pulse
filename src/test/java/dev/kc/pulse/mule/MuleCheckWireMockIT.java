package dev.kc.pulse.mule;

import java.net.http.HttpClient;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import dev.kc.pulse.mule.MuleProperties.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class MuleCheckWireMockIT {

    private WireMockServer wireMock;
    private HttpClient httpClient;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void upWhenStubReturnsExpectedStatus() {
        wireMock.stubFor(get(urlEqualTo("/order/health"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"STARTED\"}")));

        Health health = check("order", "/order/health", 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("httpStatus", 200)
                .containsEntry("expectedStatus", 200);
    }

    @Test
    void downWhenStubReturnsDifferentStatus() {
        wireMock.stubFor(get(urlEqualTo("/order/health"))
                .willReturn(aResponse().withStatus(503)));

        Health health = check("order", "/order/health", 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("httpStatus", 503)
                .containsEntry("error", "unexpected status code");
    }

    @Test
    void downWhenNoStubMatches() {
        Health health = check("order", "/no-such-path", 200, Duration.ofSeconds(2)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("httpStatus", 404);
    }

    @Test
    void downWhenStubFixedDelayExceedsTimeout() {
        wireMock.stubFor(get(urlEqualTo("/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(800)));

        Health health = check("order", "/slow", 200, Duration.ofMillis(200)).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    private MuleCheck check(String name, String path, int expected, Duration timeout) {
        Service svc = new Service();
        svc.setName(name);
        svc.setUrl(wireMock.baseUrl() + path);
        svc.setExpectedStatus(expected);
        return new MuleCheck(svc, httpClient, timeout);
    }
}
