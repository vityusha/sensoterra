package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Relay param validation: only registered sensors can be attached")
class RelayParamValidationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DeviceRepository deviceRepository;
    @Inject
    RelayRepository relayRepository;
    @Inject
    SensorRegistrationRepository sensorRegistrationRepository;
    @Inject
    MqttClientService mqttClientService;

    private String adminToken;
    private Long relayId;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    @BeforeAll
    void bootstrap() {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/bootstrap",
                    Map.of("username", "admin", "password", "admin123")));
        } catch (HttpClientResponseException ignored) {
        }
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", "admin", "password", "admin123")),
                Argument.of(Map.class));
        adminToken = (String) resp.body().get("token");

        Device block = new Device();
        block.setChipId(6000001L);
        block.setDeviceType("BLOCK");
        deviceRepository.save(block);

        Relay relay = new Relay();
        relay.setDeviceId(6000001L);
        relay.setRelayNumber((short) 1);
        relay.setName("Relay 1");
        relay.setActivity(false);
        relay.setTypeEnum("TIME");
        relay.setTimerEnabled(false);
        relayRepository.save(relay);
        relayId = relay.getId();

        SensorRegistration reg = new SensorRegistration();
        reg.setDeviceId(6000001L);
        reg.setSlot((short) 1);
        reg.setSensorChipId(6100001L);
        sensorRegistrationRepository.save(reg);
    }

    @BeforeEach
    void stubMqtt() {
        when(mqttClientService.isConnected()).thenReturn(true);
        when(mqttClientService.publish(anyString(), anyString())).thenReturn(true);
        when(mqttClientService.firmwareTime(anyLong())).thenReturn("2026-09-15T10:00:00+03:00");
    }

    @Test
    @Order(1)
    @DisplayName("Registered sensor id is accepted")
    void registeredSensorAccepted() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/relays/" + relayId + "/params",
                                Map.of("idSensor", 6100001L))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
    }

    @Test
    @Order(2)
    @DisplayName("Sensor NOT in registration list is rejected with 400")
    void unregisteredSensorRejected() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/relays/" + relayId + "/params",
                                        Map.of("idSensor", 999999L))
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(400, ex.getStatus().getCode());
    }
}
