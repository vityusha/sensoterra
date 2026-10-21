package ru.sensoterra.server;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.mqtt.MqttClientService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /health is used by the docker healthcheck, deploy.sh and external uptime monitors.
 * A lost MQTT broker must not turn it into 503 (that would roll a deploy back), and anonymous
 * callers must not see component details.
 */
@MicronautTest
@DisplayName("/health endpoint")
class HealthEndpointTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    MqttClientService mqttClientService;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    private HttpResponse<Map> health(String token) {
        HttpRequest<?> request = HttpRequest.GET("/health");
        if (token != null) {
            request = HttpRequest.GET("/health").header("Authorization", "Bearer " + token);
        }
        return client.toBlocking().exchange(request, Map.class);
    }

    @Test
    @DisplayName("UP without a token when MQTT is connected; no details")
    void upAnonymous() {
        when(mqttClientService.isConnected()).thenReturn(true);
        HttpResponse<Map> resp = health(null);
        assertEquals(200, resp.code());
        Map body = resp.body();
        assertEquals("UP", body.get("status"));
        assertNull(body.get("details"), "anonymous callers must not see component details");
    }

    @Test
    @DisplayName("DEGRADED but still 200 when MQTT is disconnected")
    void degradedWithoutMqtt() {
        when(mqttClientService.isConnected()).thenReturn(false);
        HttpResponse<Map> resp = health(null);
        assertEquals(200, resp.code());
        assertEquals("DEGRADED", resp.body().get("status"));
    }

    @Test
    @DisplayName("With a JWT the response lists the DB and MQTT components")
    void detailsWithToken() {
        when(mqttClientService.isConnected()).thenReturn(false);
        Map reg = client.toBlocking().retrieve(HttpRequest.POST("/api/auth/register",
                Map.of("username", "health-user", "password", "healthpass1")), Map.class);
        String token = (String) reg.get("token");
        assertNotNull(token);

        Map details = (Map) health(token).body().get("details");
        assertNotNull(details, "authenticated callers get details");
        assertTrue(details.containsKey("jdbc"), "details: " + details.keySet());
        Map mqtt = (Map) details.get("mqtt");
        assertEquals("DEGRADED", mqtt.get("status"));
        assertEquals("UP", ((Map) details.get("jdbc")).get("status"));
    }

    @Test
    @DisplayName("Other management endpoints stay disabled")
    void otherEndpointsDisabled() {
        for (String path : new String[]{"/env", "/beans", "/loggers", "/info", "/metrics", "/routes"}) {
            var ex = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                    () -> client.toBlocking().exchange(HttpRequest.GET(path)), path);
            assertTrue(ex.getStatus().getCode() == 404 || ex.getStatus().getCode() == 401,
                    path + " -> " + ex.getStatus());
        }
    }
}
