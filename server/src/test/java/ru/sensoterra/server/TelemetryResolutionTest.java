package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.SensorTelemetry;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.service.TelemetryAggregateService;
import ru.sensoterra.server.service.TelemetryAggregateService.Resolution;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Выбор источника телеметрии (сырые / почасовой / суточный агрегат) и статус устройства
 * на начало окна. Агрегатов в H2 нет — TelemetryAggregateService подменён моком,
 * сам SQL агрегатов проверяется на docker-стенде с TimescaleDB.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Telemetry: aggregates for long ranges, status at window start")
class TelemetryResolutionTest {

    private static final long DEVICE = 7100001L;
    private static final long FALLBACK_DEVICE = 7100002L;
    private static final long STALE_DEVICE = 7100003L;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DeviceRepository deviceRepository;

    @Inject
    TransactionOperations<Connection> tx;

    @Inject
    TelemetryAggregateService aggregates;

    private String adminToken;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    @MockBean(TelemetryAggregateService.class)
    TelemetryAggregateService aggregateService() {
        return mock(TelemetryAggregateService.class);
    }

    @BeforeAll
    void setUp() {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/bootstrap",
                    Map.of("username", "admin", "password", "admin-pass-123")));
        } catch (HttpClientResponseException ignored) {
        }
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", "admin", "password", "admin-pass-123")),
                Argument.of(Map.class));
        adminToken = (String) resp.body().get("token");

        for (long chip : new long[]{DEVICE, FALLBACK_DEVICE, STALE_DEVICE}) {
            Device d = new Device();
            d.setChipId(chip);
            d.setDeviceType("INDIVIDUAL_SENSOR");
            deviceRepository.save(d);
        }

        // device_status_log без сущности: в проде создаётся миграцией, в H2 — здесь.
        sql("CREATE TABLE IF NOT EXISTS device_status_log (" +
                "time TIMESTAMP WITH TIME ZONE NOT NULL, device_id BIGINT NOT NULL, status VARCHAR(20))");
        insertStatus(DEVICE, now.minus(Duration.ofDays(5)), "online");
        insertStatus(DEVICE, now.minus(Duration.ofDays(3)), "offline");
        insertStatus(DEVICE, now.minus(Duration.ofHours(1)), "online");
        // Журнал пуст (вычищен retention) — статус только в device.last_status.
        updateLastStatus(FALLBACK_DEVICE, "online", now.minus(Duration.ofDays(200)));
        updateLastStatus(STALE_DEVICE, "offline", now.minus(Duration.ofHours(2)));

        for (int i = 0; i < 3; i++) {
            Instant t = now.minus(Duration.ofHours(i + 1));
            sql("INSERT INTO sensor_telemetry (time, device_id, sensor_chip_id, moisture_adc) VALUES ('"
                    + Timestamp.from(t) + "', " + DEVICE + ", " + DEVICE + ", " + (1000 + i) + ")");
        }
    }

    @BeforeEach
    void resetMock() {
        reset(aggregates);
    }

    @Test
    @DisplayName("Порог разрешения: ≤7 дней — сырые, >7 — час, >90 — сутки, старше года — агрегат")
    void chooseResolution() {
        Instant n = Instant.parse("2026-10-19T00:00:00Z");
        assertEquals(Resolution.RAW, TelemetryAggregateService.choose(null, null, n));
        assertEquals(Resolution.RAW, TelemetryAggregateService.choose(n.minus(Duration.ofDays(1)), null, n));
        assertEquals(Resolution.RAW, TelemetryAggregateService.choose(n.minus(Duration.ofDays(7)), null, n));
        assertEquals(Resolution.HOUR, TelemetryAggregateService.choose(n.minus(Duration.ofDays(30)), null, n));
        assertEquals(Resolution.HOUR, TelemetryAggregateService.choose(n.minus(Duration.ofDays(90)), null, n));
        assertEquals(Resolution.DAY, TelemetryAggregateService.choose(n.minus(Duration.ofDays(400)), null, n));
        // Короткое окно, но сырых данных за него уже нет.
        Instant oldStart = n.minus(Duration.ofDays(400));
        assertEquals(Resolution.HOUR,
                TelemetryAggregateService.choose(oldStart, oldStart.plus(Duration.ofDays(1)), n));
        // Старше срока почасового агрегата — только суточный.
        Instant ancient = n.minus(Duration.ofDays(4 * 365));
        assertEquals(Resolution.DAY,
                TelemetryAggregateService.choose(ancient, ancient.plus(Duration.ofDays(1)), n));
    }

    @Test
    @DisplayName("1 день — сырые строки, агрегат не трогаем")
    void shortRangeIsRaw() {
        List<Map> rows = sensor(now.minus(Duration.ofDays(1)));
        assertEquals(3, rows.size());
        assertEquals(1000, ((Number) rows.get(0).get("moistureAdc")).intValue(), "новые первыми");
        assertNull(rows.get(0).get("resolution"));
        verify(aggregates, never()).load(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("30 дней — почасовой агрегат, 400 дней — суточный")
    void longRangesUseAggregates() {
        when(aggregates.load(any(), any(), any(), any(), any(), anyInt())).thenAnswer(inv -> {
            Resolution r = inv.getArgument(0);
            SensorTelemetry p = new SensorTelemetry();
            p.setTime(now.truncatedTo(ChronoUnit.HOURS));
            p.setDeviceId(DEVICE);
            p.setSensorChipId(DEVICE);
            p.setMoistureAdc(1234);
            p.setResolution(r.label);
            p.setSamples(120L);
            return List.of(p);
        });

        List<Map> month = sensor(now.minus(Duration.ofDays(30)));
        assertEquals(1, month.size());
        assertEquals("hour", month.get(0).get("resolution"));
        assertEquals(1234, ((Number) month.get(0).get("moistureAdc")).intValue());
        verify(aggregates).load(eq(Resolution.HOUR), eq(DEVICE), eq(DEVICE), any(), isNull(), anyInt());

        List<Map> year = sensor(now.minus(Duration.ofDays(400)));
        assertEquals("day", year.get(0).get("resolution"));
        verify(aggregates).load(eq(Resolution.DAY), eq(DEVICE), eq(DEVICE), any(), isNull(), anyInt());
    }

    @Test
    @DisplayName("Статус до начала окна — одна последняя запись журнала")
    void statusBeforeReturnsSingleLatestRow() {
        List<Map> rows = status(DEVICE, now.minus(Duration.ofDays(2)));
        assertEquals(1, rows.size());
        assertEquals("offline", rows.get(0).get("status"));
    }

    @Test
    @DisplayName("Журнал вычищен — статус на начало окна из device.last_status")
    void statusBeforeFallsBackToDeviceLastStatus() {
        List<Map> rows = status(FALLBACK_DEVICE, now.minus(Duration.ofDays(30)));
        assertEquals(1, rows.size());
        assertEquals("online", rows.get(0).get("status"));
    }

    @Test
    @DisplayName("last_status выставлен позже начала окна — статус на начало неизвестен")
    void statusBeforeIgnoresNewerLastStatus() {
        assertTrue(status(STALE_DEVICE, now.minus(Duration.ofDays(1))).isEmpty());
    }

    @Test
    @DisplayName("Журнал за окно — без ограничения одной строкой")
    void statusWindowStillReturnsAllRows() {
        List<Map> rows = client.toBlocking().retrieve(
                HttpRequest.GET("/api/telemetry/status?deviceId=" + DEVICE
                        + "&since=" + now.minus(Duration.ofDays(10))).bearerAuth(adminToken),
                Argument.listOf(Map.class));
        assertEquals(3, rows.size());
    }

    private List<Map> sensor(Instant since) {
        return client.toBlocking().retrieve(
                HttpRequest.GET("/api/telemetry/sensor?deviceId=" + DEVICE + "&sensorChipId=" + DEVICE
                        + "&since=" + since).bearerAuth(adminToken),
                Argument.listOf(Map.class));
    }

    private List<Map> status(long deviceId, Instant to) {
        return client.toBlocking().retrieve(
                HttpRequest.GET("/api/telemetry/status?deviceId=" + deviceId + "&to=" + to)
                        .bearerAuth(adminToken),
                Argument.listOf(Map.class));
    }

    private void insertStatus(long deviceId, Instant time, String status) {
        sql("INSERT INTO device_status_log (time, device_id, status) VALUES ('"
                + Timestamp.from(time) + "', " + deviceId + ", '" + status + "')");
    }

    private void updateLastStatus(long deviceId, String status, Instant at) {
        sql("UPDATE device SET last_status = '" + status + "', last_status_at = '"
                + Timestamp.from(at) + "' WHERE chip_id = " + deviceId);
    }

    private void sql(String statement) {
        tx.executeWrite(s -> {
            try (var st = s.getConnection().createStatement()) {
                st.execute(statement);
            }
            return null;
        });
    }
}
