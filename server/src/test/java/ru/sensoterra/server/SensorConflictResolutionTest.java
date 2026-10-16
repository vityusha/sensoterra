package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.CommandLogRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.service.DeviceStateStore;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sensor conflict resolution over MQTT UNREG")
class SensorConflictResolutionTest {

    static final long BLOCK_A = 7000101L;
    static final long BLOCK_B = 7000102L;
    static final long SENSOR_CONFLICT = 6050001L;
    static final long SENSOR_SINGLE = 6050002L;
    static final long SENSOR_BAD_KEEP = 6050003L;
    static final long SENSOR_OFFLINE = 6050004L;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DeviceRepository deviceRepository;
    @Inject
    SensorRegistrationRepository sensorRegistrationRepository;
    @Inject
    DeviceStateStore stateStore;
    @Inject
    MqttClientService mqttClientService;
    @Inject
    CommandLogRepository commandLogRepository;

    private String adminToken;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    @BeforeAll
    void setup() {
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

        saveBlock(BLOCK_A);
        saveBlock(BLOCK_B);
        register(BLOCK_A, (short) 1, SENSOR_CONFLICT);
        register(BLOCK_B, (short) 2, SENSOR_CONFLICT);
        register(BLOCK_A, (short) 3, SENSOR_SINGLE);
        register(BLOCK_A, (short) 4, SENSOR_BAD_KEEP);
        register(BLOCK_B, (short) 5, SENSOR_BAD_KEEP);
        register(BLOCK_A, (short) 6, SENSOR_OFFLINE);
        register(BLOCK_B, (short) 7, SENSOR_OFFLINE);
    }

    private void saveBlock(Long chipId) {
        Device block = new Device();
        block.setChipId(chipId);
        block.setDeviceType("BLOCK");
        deviceRepository.save(block);
    }

    private void register(Long blockId, Short slot, Long chipId) {
        SensorRegistration reg = new SensorRegistration();
        reg.setDeviceId(blockId);
        reg.setSlot(slot);
        reg.setSensorChipId(chipId);
        sensorRegistrationRepository.save(reg);
    }

    @BeforeEach
    void stubMqtt() {
        when(mqttClientService.isConnected()).thenReturn(true);
        when(mqttClientService.publishUnregister(anyLong(), anyLong())).thenReturn(true);
        when(mqttClientService.firmwareTime(anyLong())).thenReturn("2026-09-15T10:00:00+03:00");
    }

    @Test
    @Order(1)
    @DisplayName("Resolve unregisters sensor from all blocks except the kept one")
    void resolveRemovesOtherBlocks() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/sensors/" + SENSOR_CONFLICT + "/resolve-conflict",
                                Map.of("keepBlockId", BLOCK_A))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        assertEquals(BLOCK_A, ((Number) resp.body().get("keepBlockId")).longValue());
        assertEquals(List.of(BLOCK_B).toString(), resp.body().get("unregisteredBlocks").toString());

        verify(mqttClientService).publishUnregister(eq(BLOCK_B), eq(SENSOR_CONFLICT));
        verify(mqttClientService, never()).publishUnregister(eq(BLOCK_A), anyLong());

        var logs = commandLogRepository.findByDeviceIdAndCommandType(BLOCK_B, "SENSOR_UNREG");
        assertFalse(logs.isEmpty());
        assertEquals(BLOCK_B + "/UNREG", logs.get(0).getTopic());
    }

    @Test
    @Order(2)
    @DisplayName("Sensor registered on a single block is not a conflict")
    void singleBlockRejected() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/sensors/" + SENSOR_SINGLE + "/resolve-conflict",
                                        Map.of("keepBlockId", BLOCK_A))
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(400, ex.getStatus().getCode());
        verify(mqttClientService, never()).publishUnregister(anyLong(), anyLong());
    }

    @Test
    @Order(3)
    @DisplayName("keepBlockId not among registered blocks is rejected")
    void badKeepBlockRejected() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/sensors/" + SENSOR_BAD_KEEP + "/resolve-conflict",
                                        Map.of("keepBlockId", 999999L))
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(400, ex.getStatus().getCode());
        verify(mqttClientService, never()).publishUnregister(anyLong(), anyLong());
    }

    @Test
    @Order(4)
    @DisplayName("Offline blocks are reported so the UI can warn")
    void offlineBlockReported() {
        stateStore.setStatus(BLOCK_B, "offline");
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/sensors/" + SENSOR_OFFLINE + "/resolve-conflict",
                                Map.of("keepBlockId", BLOCK_A))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        assertEquals(List.of(BLOCK_B).toString(), resp.body().get("offlineBlocks").toString());
        verify(mqttClientService).publishUnregister(eq(BLOCK_B), eq(SENSOR_OFFLINE));
    }
}