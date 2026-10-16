package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.TelemetryService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Deleting a standalone sensor clears retained topics and database rows")
class DeviceDeletionTest {

    private static final long SENSOR_ID = 5000201L;
    private static final long BLOCK_ID = 5000202L;
    private static final long SHARED_SENSOR_ID = 5000203L;
    private static final long OTHER_BLOCK_ID = 5000204L;
    private static final long EXCLUSIVE_SENSOR_ID = 5000205L;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DeviceRepository deviceRepository;
    @Inject
    SensorRepository sensorRepository;
    @Inject
    SensorRegistrationRepository sensorRegistrationRepository;
    @Inject
    RelayRepository relayRepository;
    @Inject
    MqttClientService mqttClientService;
    @Inject
    TelemetryService telemetryService;

    private String adminToken;
    private String clientToken;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientServiceMock() {
        return mock(MqttClientService.class);
    }

    @BeforeAll
    void bootstrap() throws Exception {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/bootstrap",
                    Map.of("username", "admin", "password", "admin123")));
        } catch (HttpClientResponseException ignored) {
        }
        adminToken = login("admin", "admin123");

        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/register",
                    Map.of("username", "deletion-client", "password", "client123"))
                    .header("Authorization", "Bearer " + adminToken));
        } catch (HttpClientResponseException ignored) {
        }
        clientToken = login("deletion-client", "client123");

        Device sensor = new Device();
        sensor.setChipId(SENSOR_ID);
        sensor.setDeviceType("INDIVIDUAL_SENSOR");
        sensor.setName("Deletable sensor");
        deviceRepository.save(sensor);

        Sensor row = new Sensor();
        row.setDeviceId(SENSOR_ID);
        row.setSensorChipId(SENSOR_ID);
        row.setName("Deletable sensor");
        sensorRepository.save(row);

        Device block = new Device();
        block.setChipId(BLOCK_ID);
        block.setDeviceType("BLOCK");
        deviceRepository.save(block);

        Device otherBlock = new Device();
        otherBlock.setChipId(OTHER_BLOCK_ID);
        otherBlock.setDeviceType("BLOCK");
        deviceRepository.save(otherBlock);

        Device blockSensor = new Device();
        blockSensor.setChipId(SHARED_SENSOR_ID);
        blockSensor.setDeviceType("BLOCK_SENSOR");
        deviceRepository.save(blockSensor);

        Device exclusiveSensor = new Device();
        exclusiveSensor.setChipId(EXCLUSIVE_SENSOR_ID);
        exclusiveSensor.setDeviceType("BLOCK_SENSOR");
        deviceRepository.save(exclusiveSensor);

        saveSensorRow(BLOCK_ID, SHARED_SENSOR_ID, (short) 1);
        saveSensorRow(OTHER_BLOCK_ID, SHARED_SENSOR_ID, (short) 1);
        saveSensorRow(BLOCK_ID, EXCLUSIVE_SENSOR_ID, (short) 2);

        saveRegistration(BLOCK_ID, (short) 1, SHARED_SENSOR_ID);
        saveRegistration(OTHER_BLOCK_ID, (short) 1, SHARED_SENSOR_ID);
        saveRegistration(BLOCK_ID, (short) 2, EXCLUSIVE_SENSOR_ID);

        Relay relay = new Relay();
        relay.setDeviceId(BLOCK_ID);
        relay.setRelayNumber((short) 1);
        relay.setSensorId(SHARED_SENSOR_ID);
        relayRepository.save(relay);

        insertTelemetry(SENSOR_ID, SENSOR_ID, Instant.now().minusSeconds(60));
        insertTelemetry(SENSOR_ID, SENSOR_ID, Instant.now().minusSeconds(30));
        insertTelemetry(BLOCK_ID, EXCLUSIVE_SENSOR_ID, Instant.now().minusSeconds(10));
        insertTelemetry(OTHER_BLOCK_ID, SHARED_SENSOR_ID, Instant.now().minusSeconds(5));
    }

    private void saveSensorRow(long deviceId, long chipId, short number) {
        Sensor row = new Sensor();
        row.setDeviceId(deviceId);
        row.setSensorChipId(chipId);
        row.setSensorNumber(number);
        row.setName("Block sensor " + chipId);
        sensorRepository.save(row);
    }

    private void saveRegistration(long deviceId, short slot, long chipId) {
        SensorRegistration registration = new SensorRegistration();
        registration.setDeviceId(deviceId);
        registration.setSlot(slot);
        registration.setSensorChipId(chipId);
        sensorRegistrationRepository.save(registration);
    }

    private void insertTelemetry(long deviceId, long chipId, Instant time) {
        TelemetryService.SensorData data = new TelemetryService.SensorData();
        data.name = "Deletable sensor";
        data.moistureAdc = 1234;
        data.numMessage = 7;
        telemetryService.onSensorEvent(deviceId, chipId, data, time);
    }

    private long telemetryCount(long chipId) {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/telemetry/sensor?deviceId=" + chipId)
                        .header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        return resp.body().size();
    }

    private List<Long> asLongs(Object value) {
        List<?> raw = (List<?>) value;
        List<Long> result = new ArrayList<>();
        for (Object item : raw) {
            result.add(((Number) item).longValue());
        }
        return result;
    }

    private String login(String username, String password) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", username, "password", password)),
                Argument.of(Map.class));
        return (String) resp.body().get("token");
    }

    @Test
    @Order(1)
    @DisplayName("Non-admin cannot delete a device")
    void nonAdminForbidden() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.DELETE("/api/devices/" + SENSOR_ID)
                        .header("Authorization", "Bearer " + clientToken),
                        Argument.mapOf(String.class, Object.class)));
        assertEquals(403, ex.getStatus().getCode());
        assertTrue(deviceRepository.existsById(SENSOR_ID));
    }

    @Test
    @Order(2)
    @DisplayName("Deleting an individual sensor clears retained and all database rows")
    void adminDeletesSensor() throws Exception {
        when(mqttClientService.clearRetainedForDevice(SENSOR_ID))
                .thenReturn(new MqttClientService.RetainedClearResult(50, 50));
        when(mqttClientService.isConnected()).thenReturn(true);
        assertEquals(2, telemetryCount(SENSOR_ID));

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + SENSOR_ID)
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class));

        assertEquals(200, resp.getStatus().getCode());
        assertEquals(50, ((Number) resp.body().get("retainedTopicsCleared")).intValue());
        assertEquals(50, ((Number) resp.body().get("retainedTopicsTotal")).intValue());
        assertEquals("INDIVIDUAL_SENSOR", resp.body().get("deviceType"));

        Map<String, Object> rows = (Map<String, Object>) resp.body().get("deletedRows");
        assertEquals(2, ((Number) rows.get("sensor_telemetry")).intValue());
        assertEquals(1, ((Number) rows.get("sensor")).intValue());

        verify(mqttClientService).clearRetainedForDevice(SENSOR_ID);
    }

    @Test
    @Order(3)
    @DisplayName("Deleted device and its telemetry are gone")
    void rowsAreGone() throws Exception {
        assertFalse(deviceRepository.existsById(SENSOR_ID));
        assertTrue(sensorRepository.findByDeviceId(SENSOR_ID).isEmpty());
        assertEquals(0, telemetryCount(SENSOR_ID));
    }

    @Test
    @Order(4)
    @DisplayName("Deleted device disappears from the device list")
    void deviceListIsClean() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertNull(findByChip(resp.body(), SENSOR_ID));
    }

    @Test
    @Order(5)
    @DisplayName("Deleting a block removes its slots and exclusive sensors, keeps a sensor shared with another block")
    void adminDeletesBlockCascade() throws Exception {
        when(mqttClientService.clearRetainedForDevice(BLOCK_ID))
                .thenReturn(new MqttClientService.RetainedClearResult(46, 46));
        when(mqttClientService.clearRetainedForDevice(EXCLUSIVE_SENSOR_ID))
                .thenReturn(new MqttClientService.RetainedClearResult(46, 46));

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + BLOCK_ID)
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class));

        assertEquals(200, resp.getStatus().getCode());
        assertEquals("BLOCK", resp.body().get("deviceType"));
        assertEquals(List.of(SHARED_SENSOR_ID, EXCLUSIVE_SENSOR_ID), asLongs(resp.body().get("sensorChipIds")));
        assertEquals(List.of(SHARED_SENSOR_ID), asLongs(resp.body().get("sharedSensorChipIds")));
        assertEquals(92, ((Number) resp.body().get("retainedTopicsCleared")).intValue());

        Map<String, Object> rows = (Map<String, Object>) resp.body().get("deletedRows");
        assertEquals(2, ((Number) rows.get("sensor")).intValue());
        assertEquals(2, ((Number) rows.get("sensor_registration")).intValue());
        assertEquals(2, ((Number) rows.get("device")).intValue());
        assertEquals(1, ((Number) rows.get("relay")).intValue());
        assertEquals(1, ((Number) rows.get("sensor_telemetry")).intValue());

        verify(mqttClientService).clearRetainedForDevice(BLOCK_ID);
        verify(mqttClientService).clearRetainedForDevice(EXCLUSIVE_SENSOR_ID);
        verify(mqttClientService, never()).clearRetainedForDevice(SHARED_SENSOR_ID);
    }

    @Test
    @Order(6)
    @DisplayName("Block and its exclusive sensor are gone, the shared sensor survives intact")
    void blockRowsAreGone() {
        assertFalse(deviceRepository.existsById(BLOCK_ID));
        assertFalse(deviceRepository.existsById(EXCLUSIVE_SENSOR_ID));
        assertTrue(sensorRepository.findByDeviceId(BLOCK_ID).isEmpty());
        assertTrue(sensorRepository.findBySensorChipId(EXCLUSIVE_SENSOR_ID).isEmpty());
        assertTrue(sensorRegistrationRepository.findByDeviceId(BLOCK_ID).isEmpty());
        assertEquals(0, telemetryCount(BLOCK_ID));

        assertTrue(deviceRepository.existsById(SHARED_SENSOR_ID));
        assertTrue(deviceRepository.existsById(OTHER_BLOCK_ID));
        assertFalse(sensorRepository.findByDeviceId(OTHER_BLOCK_ID).isEmpty());
        assertEquals(1, sensorRegistrationRepository.findBySensorChipId(SHARED_SENSOR_ID).size());
        assertEquals(OTHER_BLOCK_ID, sensorRegistrationRepository.findBySensorChipId(SHARED_SENSOR_ID)
                .get(0).getDeviceId());
        assertEquals(1, telemetryCount(OTHER_BLOCK_ID));
    }

    @Test
    @Order(7)
    @DisplayName("Deleting an unknown device returns 404")
    void unknownDevice() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.DELETE("/api/devices/5999999")
                        .header("Authorization", "Bearer " + adminToken),
                        Argument.mapOf(String.class, Object.class)));
        assertEquals(404, ex.getStatus().getCode());
    }

    private Map findByChip(List<Map> list, long chipId) {
        for (Map m : list) {
            if (chipId == ((Number) m.get("chipId")).longValue()) {
                return m;
            }
        }
        return null;
    }
}
