package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.service.TelemetryService;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * EVENT_R — событие канала (включение по таймеру, по датчику, ручное): оно меняет фактическое
 * состояние канала. STATE_R приходит только при реконнекте и настройке, поэтому состояние из
 * события должно попадать в relay.state, иначе страница канала показывала бы «вкл» до следующего
 * подключения блока.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RelayEventStateTest {

    @Inject
    TelemetryService telemetryService;
    @Inject
    DeviceRepository deviceRepository;
    @Inject
    RelayRepository relayRepository;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    private static final long DEVICE = 8300001L;
    private static final short RELAY = 3;

    @BeforeAll
    void bootstrap() {
        Device block = new Device();
        block.setChipId(DEVICE);
        block.setDeviceType("BLOCK");
        deviceRepository.save(block);
    }

    private Relay relay() {
        return relayRepository.findByDeviceIdAndRelayNumber(DEVICE, RELAY)
                .orElseThrow(() -> new AssertionError("relay not upserted"));
    }

    private void upsertSnapshot() {
        telemetryService.upsertRelay(DEVICE, new TelemetryService.RelayData(RELAY)
                .with("R3", (short) 1, true, "S_ONE", false, (short) 0,
                        java.time.LocalTime.of(10, 0), (short) 1,
                        java.time.LocalTime.of(11, 0), (short) 1,
                        (short) 0, null,
                        java.time.LocalTime.of(8, 0), java.time.LocalTime.of(12, 0),
                        0, 0, 0, -50, 3.3f, 5.0f));
    }

    @Test
    @Order(1)
    @DisplayName("STATE_R (snapshot) stores relay row with state")
    void stateRSnapshot() {
        upsertSnapshot();
        assertEquals((short) 1, relay().getState().shortValue());
        // EVENT_R с тем же состоянием ничего не ломает.
        telemetryService.onRelayEvent(DEVICE, RELAY, Instant.now(), 1, false, -52, 3.2f, 5.1f);
        assertEquals((short) 1, relay().getState().shortValue());
    }

    @Test
    @Order(2)
    @DisplayName("EVENT_R updates relay.state to off even without STATE_R")
    void eventRTurnsOff() {
        upsertSnapshot();
        telemetryService.onRelayEvent(DEVICE, RELAY, Instant.now(), 0, false, -52, 3.2f, 5.1f);
        assertEquals((short) 0, relay().getState().shortValue());
    }

    @Test
    @Order(3)
    @DisplayName("EVENT_R with state updates to on")
    void eventRTurnsOn() {
        upsertSnapshot();
        telemetryService.onRelayEvent(DEVICE, RELAY, Instant.now(), 1, false, -52, 3.2f, 5.1f);
        assertEquals((short) 1, relay().getState().shortValue());
    }
}