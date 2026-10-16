package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Registered sensors + sensor catalog conflict")
class RegisteredSensorsTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DeviceRepository deviceRepository;
    @Inject
    SensorRepository sensorRepository;
    @Inject
    SensorRegistrationRepository sensorRegistrationRepository;

    private String adminToken;
    private Instant staleLastData;
    private Instant neverData;

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

        Device block1 = new Device();
        block1.setChipId(5000001L);
        block1.setDeviceType("BLOCK");
        deviceRepository.save(block1);

        Device block2 = new Device();
        block2.setChipId(5000002L);
        block2.setDeviceType("BLOCK");
        deviceRepository.save(block2);

        Device individual = new Device();
        individual.setChipId(5000101L);
        individual.setDeviceType("INDIVIDUAL_SENSOR");
        individual.setName("Wu sensor");
        deviceRepository.save(individual);

        Sensor s = new Sensor();
        s.setDeviceId(5000001L);
        s.setSensorChipId(5000101L);
        s.setSensorNumber((short) 1);
        s.setName("Wu sensor");
        s.setSensorType((short) 0);
        sensorRepository.save(s);

        SensorRegistration reg1 = new SensorRegistration();
        reg1.setDeviceId(5000001L);
        reg1.setSlot((short) 1);
        reg1.setSensorChipId(5000101L);
        sensorRegistrationRepository.save(reg1);

        SensorRegistration reg2 = new SensorRegistration();
        reg2.setDeviceId(5000002L);
        reg2.setSlot((short) 1);
        reg2.setSensorChipId(5000101L);
        sensorRegistrationRepository.save(reg2);
    }

    @Test
    @Order(1)
    @DisplayName("registered-sensors endpoint returns slot list")
    void registeredSensors() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/5000001/registered-sensors")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        assertEquals(1, resp.body().size());
        Map entry = (Map) resp.body().get(0);
        assertEquals(5000101L, ((Number) entry.get("sensorChipId")).longValue());
        assertEquals(1, ((Number) entry.get("slot")).intValue());
    }

    @Test
    @Order(2)
    @DisplayName("Catalog does not mark block-registered sensor as individual")
    void catalogIndividual() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/sensors/catalog").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map entry = findCatalogEntry(resp.body(), 5000101L);
        assertNotNull(entry);
        assertEquals(false, entry.get("individual"));
    }

    @Test
    @Order(3)
    @DisplayName("Catalog shows conflict when sensor registered on 2 blocks")
    void catalogConflict() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/sensors/catalog").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        Map entry = findCatalogEntry(resp.body(), 5000101L);
        assertEquals(true, entry.get("conflict"));
        List blockRoles = (List) entry.get("blockRoles");
        assertEquals(2, blockRoles.size());
    }

    /**
     * Данные последних сообщений — метка, по которой каталог считает «жив» датчик.
     * Фикстуры создаются до тестов: записи, сохранённые внутри тестовой транзакции,
     * не видны HTTP-запросу (каждый тест откатывается).
     */
    @BeforeAll
    @Order(1)
    void catalogFixtures() {
        staleLastData = Instant.now().minus(Duration.ofDays(2));
        neverData = Instant.now().minus(Duration.ofMinutes(90));
        blockSlotSensor(5000201L, (short) 7, staleLastData, Instant.now());
        blockSlotSensor(5000202L, (short) 8, null, neverData);
    }

    @Test
    @Order(4)
    @DisplayName("Catalog lastTs follows last_time and ignores newer updated_at")
    void catalogLastTsUsesLastTime() {
        Map entry = catalogEntry(5000201L);
        assertNotNull(entry);
        assertEquals(staleLastData.toEpochMilli(), ((Number) entry.get("lastTs")).longValue());
    }

    @Test
    @Order(5)
    @DisplayName("Catalog falls back to updated_at when sensor never sent data")
    void catalogLastTsFallsBackToUpdatedAt() {
        Map entry = catalogEntry(5000202L);
        assertNotNull(entry);
        assertEquals(neverData.toEpochMilli(), ((Number) entry.get("lastTs")).longValue());
    }

    @Test
    @Order(6)
    @DisplayName("Catalog exposes server clock so client can correct its own skew")
    void catalogExposesServerNow() {
        Map entry = catalogEntry(5000201L);
        assertNotNull(entry);
        long serverNow = ((Number) entry.get("serverNow")).longValue();
        assertTrue(Math.abs(System.currentTimeMillis() - serverNow) < 60000,
                "serverNow должен совпадать с серверным временем");
    }

    private void blockSlotSensor(long chipId, short slot, Instant lastTime, Instant updatedAt) {
        // Датчик в свободном слоте видимого блока: индивидуальные датчики без
        // device_access этому пользователю не видны, а блок 5000002 — виден.
        SensorRegistration reg = new SensorRegistration();
        reg.setDeviceId(5000002L);
        reg.setSlot(slot);
        reg.setSensorChipId(chipId);
        sensorRegistrationRepository.save(reg);

        Sensor s = new Sensor();
        s.setDeviceId(5000002L);
        s.setSensorChipId(chipId);
        s.setSensorNumber(slot);
        s.setSend(600);
        s.setLastTime(lastTime);
        s.setUpdatedAt(updatedAt);
        sensorRepository.save(s);
    }

    private Map catalogEntry(long chipId) {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/sensors/catalog").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map found = findCatalogEntry(resp.body(), chipId);
        assertNotNull(found, () -> "каталог не содержит " + chipId + ": " + resp.body());
        return found;
    }

    @SuppressWarnings("unchecked")
    private Map findCatalogEntry(List<Map> list, long chipId) {
        for (Map m : list) {
            if (chipId == ((Number) m.get("chipId")).longValue()) {
                return m;
            }
        }
        return null;
    }
}
