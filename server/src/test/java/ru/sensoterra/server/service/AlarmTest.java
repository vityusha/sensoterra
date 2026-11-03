package ru.sensoterra.server.service;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.entity.Alarm;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.AlarmRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@MicronautTest(transactional = false)
@DisplayName("Alarms: gaps, immersion level, block offline; list, read, delete")
class AlarmTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AlarmService alarms;

    @Inject
    AlarmRepository alarmRepository;

    @Inject
    DeviceRepository deviceRepository;

    @Inject
    SensorRepository sensorRepository;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    private String adminToken;
    private String ownerToken;
    private String viewerToken;
    private String strangerToken;
    private long chipId;

    private Map post(String path, Object body, String token) {
        return client.toBlocking().retrieve(auth(HttpRequest.POST(path, body), token), Map.class);
    }

    private static MutableHttpRequest<?> auth(MutableHttpRequest<?> request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private int status(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(auth(request, token)).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private String register(String name) {
        return (String) post("/api/auth/register", Map.of("username", name, "password", "pw-123456"), null).get("token");
    }

    private Long me(String token) {
        return ((Number) client.toBlocking().retrieve(auth(HttpRequest.GET("/api/auth/me"), token), Map.class)
                .get("id")).longValue();
    }

    private List<Map> list(String type, String token) {
        return client.toBlocking().retrieve(auth(HttpRequest.GET("/api/alarms?type=" + type), token),
                Argument.listOf(Map.class));
    }

    private long unseen(String token) {
        return ((Number) client.toBlocking().retrieve(auth(HttpRequest.GET("/api/alarms/summary"), token), Map.class)
                .get("unseen")).longValue();
    }

    private static long newChip() {
        return 770000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);
    }

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        adminToken = (String) post("/api/test/admin",
                Map.of("username", "alarm-admin-" + run, "password", "admin-pass-123"), null).get("token");
        ownerToken = register("alarm-owner-" + run);
        viewerToken = register("alarm-viewer-" + run);
        strangerToken = register("alarm-stranger-" + run);
        chipId = newChip();
        post("/api/test/admin/users/" + me(ownerToken) + "/devices",
                Map.of("chipId", chipId, "deviceType", "INDIVIDUAL_SENSOR"), adminToken);
        post("/api/test/admin/users/" + me(viewerToken) + "/devices",
                Map.of("chipId", chipId, "deviceType", "INDIVIDUAL_SENSOR", "accessRole", "VIEWER"), adminToken);
    }

    private Sensor sensor(long chip, Instant lastTime, int send) {
        Sensor s = new Sensor();
        s.setDeviceId(chip);
        s.setSensorChipId(chip);
        s.setSensorType((short) 0);
        s.setSend(send);
        s.setLastTime(lastTime);
        return sensorRepository.save(s);
    }

    private Alarm open(long chip, String type) {
        return alarmRepository.findFirstByChipIdAndAlarmTypeAndEndedAtIsNull(chip, type).orElse(null);
    }

    @Test
    @DisplayName("Silence for 2 send intervals + 10 min opens a gap once; live data closes it")
    void gap() {
        Instant lastTime = Instant.now().minusSeconds(3600);
        sensor(chipId, lastTime, 600);
        alarms.checkGaps(Instant.now());
        Alarm gap = open(chipId, AlarmService.GAP);
        assertNotNull(gap);
        assertEquals(lastTime.plusSeconds(2 * 600 + 600).toEpochMilli(), gap.getStartedAt().toEpochMilli());

        alarms.checkGaps(Instant.now());
        assertEquals(1, alarmRepository.findAll().stream()
                .filter(a -> a.getChipId() == chipId && AlarmService.GAP.equals(a.getAlarmType())).count(),
                "an open gap is not duplicated");

        alarms.onSensorData(chipId, chipId, 1000);
        assertNull(open(chipId, AlarmService.GAP));
    }

    @Test
    @DisplayName("Not in the soil: ADC above the air/dry middle opens LEVEL with the reading, updates it, closes")
    void level() {
        sensor(chipId, Instant.now(), 600);
        // Default calibration: air 1650, dry 1550 -> limit 1600.
        alarms.onSensorData(chipId, chipId, 1620);
        assertEquals(1620.0, open(chipId, AlarmService.LEVEL).getValue());
        alarms.onSensorData(chipId, chipId, 1640);
        assertEquals(1640.0, open(chipId, AlarmService.LEVEL).getValue());
        alarms.onSensorData(chipId, chipId, 1200);
        assertNull(open(chipId, AlarmService.LEVEL));
    }

    @Test
    @DisplayName("A block going offline opens OFFLINE, online closes it; standalone sensors get none")
    void offline() {
        long block = newChip();
        Device d = new Device();
        d.setChipId(block);
        d.setDeviceType("BLOCK");
        deviceRepository.save(d);
        alarms.onDeviceStatus(block, "offline");
        assertNotNull(open(block, AlarmService.OFFLINE));
        alarms.onDeviceStatus(block, "online");
        assertNull(open(block, AlarmService.OFFLINE));

        alarms.onDeviceStatus(chipId, "offline");
        assertNull(open(chipId, AlarmService.OFFLINE));
    }

    @Test
    @DisplayName("List, bell, read and delete follow device access")
    void api() {
        alarms.raise(chipId, chipId, AlarmService.GAP, Instant.now().minusSeconds(60), null);

        List<Map> rows = list("GAP", ownerToken);
        assertEquals(1, rows.size());
        Map row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        assertNull(row.get("endedAt"));
        assertEquals(false, row.get("canDelete"), "an active alarm can't be deleted");
        assertTrue(list("GAP", strangerToken).isEmpty());
        assertEquals(404, status(HttpRequest.POST("/api/alarms/" + id + "/read", Map.of()), strangerToken));

        assertTrue(unseen(ownerToken) >= 1);
        assertEquals(204, status(HttpRequest.POST("/api/alarms/seen", Map.of()), ownerToken));
        assertEquals(0, unseen(ownerToken));
        assertEquals(409, status(HttpRequest.DELETE("/api/alarms/" + id), ownerToken));

        alarms.clear(chipId, AlarmService.GAP, Instant.now());
        assertEquals(1, unseen(ownerToken), "a finished alarm is new again");
        row = list("GAP", ownerToken).get(0);
        assertNotNull(row.get("endedAt"));
        assertEquals(true, row.get("canDelete"));
        assertEquals(false, row.get("read"));
        assertEquals(false, list("GAP", viewerToken).get(0).get("canDelete"));
        assertEquals(403, status(HttpRequest.DELETE("/api/alarms/" + id), viewerToken));

        assertEquals(204, status(HttpRequest.POST("/api/alarms/" + id + "/read", Map.of()), ownerToken));
        assertEquals(true, list("GAP", ownerToken).get(0).get("read"));
        assertEquals(false, list("GAP", viewerToken).get(0).get("read"), "read is per account");

        Map deleted = client.toBlocking().retrieve(auth(HttpRequest.DELETE("/api/alarms?type=GAP"), ownerToken), Map.class);
        assertEquals(1, ((Number) deleted.get("deleted")).intValue());
        assertTrue(list("GAP", ownerToken).isEmpty());
        assertEquals(400, status(HttpRequest.GET("/api/alarms?type=NOPE"), ownerToken));
    }

    @Test
    @DisplayName("Read all marks only finished alarms of the tab, for the caller only")
    void readAll() {
        long other = newChip();
        post("/api/test/admin/users/" + me(ownerToken) + "/devices",
                Map.of("chipId", other, "deviceType", "INDIVIDUAL_SENSOR"), adminToken);
        alarms.raise(chipId, chipId, AlarmService.GAP, Instant.now().minusSeconds(600), null);
        alarms.clear(chipId, AlarmService.GAP, Instant.now());
        alarms.raise(other, other, AlarmService.GAP, Instant.now().minusSeconds(60), null);
        alarms.raise(chipId, chipId, AlarmService.LEVEL, Instant.now().minusSeconds(600), 1700.0);
        alarms.clear(chipId, AlarmService.LEVEL, Instant.now());

        Map result = post("/api/alarms/read?type=GAP", Map.of(), ownerToken);
        assertEquals(1, ((Number) result.get("read")).intValue(), "the active gap stays unread");
        assertEquals(0, ((Number) post("/api/alarms/read?type=GAP", Map.of(), ownerToken).get("read")).intValue());
        for (Map row : list("GAP", ownerToken)) {
            assertEquals(row.get("endedAt") != null, row.get("read"));
        }
        assertEquals(false, list("LEVEL", ownerToken).get(0).get("read"), "other tabs are untouched");
        assertEquals(false, list("GAP", viewerToken).stream()
                .filter(r -> ((Number) r.get("chipId")).longValue() == chipId).findFirst().orElseThrow().get("read"));
    }
}
