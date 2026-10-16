package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.service.MqttMessageProcessor;
import ru.sensoterra.server.service.RelayCommandConfirmationService;
import ru.sensoterra.server.service.TelemetryService;
import ru.sensoterra.server.ws.DeviceStatusWebSocketBroadcaster;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@MicronautTest
@DisplayName("MQTT message parsing")
class MqttMessageProcessorTest {

    @Inject
    MqttMessageProcessor processor;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    @MockBean(TelemetryService.class)
    TelemetryService telemetryService() {
        return mock(TelemetryService.class);
    }

    @MockBean(DeviceStatusWebSocketBroadcaster.class)
    DeviceStatusWebSocketBroadcaster broadcaster() {
        return mock(DeviceStatusWebSocketBroadcaster.class);
    }

    @MockBean(RelayCommandConfirmationService.class)
    RelayCommandConfirmationService confirmation() {
        return mock(RelayCommandConfirmationService.class);
    }

    @Inject
    TelemetryService telemetryService;

    @Inject
    DeviceRepository deviceRepository;

    @Inject
    RelayCommandConfirmationService confirmation;

    @Test
    @DisplayName("SENSORS topic parses JSON and calls updateRegisteredSensors")
    void sensorsTopic() {
        String payload = "{\"S1\": 6788944, \"S2\": 86788797, \"S3\": 0, \"S4\": 0, \"S5\": 0, \"S6\": 0, \"S7\": 0, \"S8\": 0}";
        processor.process("13034338/SENSORS", payload, false);

        verify(telemetryService, atLeastOnce()).updateRegisteredSensors(
                eq(13034338L),
                any());
    }

    @Test
    @DisplayName("SENSORS with zero slots skips zeros")
    void sensorsZeroValues() {
        String payload = "{\"S1\": 6021076, \"S2\": 0}";
        processor.process("9999/SENSORS", payload, false);

        verify(telemetryService).updateRegisteredSensors(eq(9999L), any());
    }

    @Test
    @DisplayName("STATUS topic updates state store, saves status and broadcasts")
    void statusTopic() {
        processor.process("13034338/STATUS", "online", false);
        verify(telemetryService).saveDeviceStatus(13034338L, "online");
    }

    @Test
    @DisplayName("PING topic is heartbeat, ignored")
    void pingTopic() {
        processor.process("13034338/PING", "", false);
        verifyNoInteractions(telemetryService);
    }

    @Test
    @DisplayName("EVENT_R topic with relay payload stores event")
    void relayEventTopic() {
        String payload = "{\"Relay\":1,\"State\":1,\"Fault\":false,\"Time\":\"15-09-2026 10:00:00\",\"RSSI_R\":-50,\"Vbat\":3.3,\"Vacc\":5.0}";
        processor.process("13034338/EVENT_R1", payload, false);
        verify(telemetryService).onRelayEvent(
                eq(13034338L), eq(1), any(), eq(1),
                eq(false), eq(-50), eq(3.3f), eq(5.0f));
    }

    @Test
    @DisplayName("EVENT_S topic with sensor payload stores event")
    void sensorEventTopic() {
        when(telemetryService.isSensorRegisteredInSlot(13034338L, 1, 6021076L)).thenReturn(true);
        String payload = "{\"ID\":6021076,\"Name\":\"Test\",\"V0\":410,\"V1\":23.5,\"Sleep\":60,\"Send\":600}";
        processor.process("13034338/EVENT_S1", payload, false);
        verify(telemetryService).upsertDevice(13034338L, "BLOCK");
        verify(telemetryService).onSensorEvent(eq(13034338L), eq(6021076L), any(), any());
    }

    @Test
    @DisplayName("EVENT_S from unregistered slot is ignored")
    void sensorEventUnregisteredSlot() {
        when(telemetryService.isSensorRegisteredInSlot(13034338L, 2, 6021076L)).thenReturn(false);
        String payload = "{\"ID\":6021076,\"Name\":\"Test\",\"V0\":410,\"V1\":23.5,\"Sleep\":60,\"Send\":600}";
        processor.process("13034338/EVENT_S2", payload, false);
        verify(telemetryService, never()).onSensorEvent(any(), any(), any(), any());
        verify(telemetryService, never()).upsertDevice(any(), any());
    }

    @Test
    @DisplayName("EVENT_S without ID is ignored")
    void sensorEventNoId() {
        String payload = "{\"Name\":\"x\",\"V0\":410}";
        processor.process("13034338/EVENT_S1", payload, false);
        verify(telemetryService, never()).onSensorEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Malformed JSON is ignored without exception")
    void malformedJson() {
        processor.process("13034338/EVENT_R1", "{{{{not json", false);
        verifyNoInteractions(telemetryService);
    }

    @Test
    @DisplayName("Non-numeric device id in topic is ignored")
    void nonNumericDevice() {
        processor.process("abc/SENSORS", "{}", false);
        verifyNoInteractions(telemetryService);
    }

    @Test
    @DisplayName("STATE_R Zone is stored as device timezone offset (inverted)")
    void relayStateStoresZone() {
        processor.process("7000001/STATE_R1",
                "{\"Relay\":1,\"State\":0,\"Activity\":\"Yes\",\"Zone\":-180}", false);
        Device device = deviceRepository.findByChipId(7000001L).orElseThrow();
        assertEquals(180, device.getTzOffsetMinutes());
    }

    @Test
    @DisplayName("EVENT_R time is parsed in the device timezone from STATE_R Zone")
    void relayEventTimeParsedInDeviceZone() {
        processor.process("7000002/STATE_R1",
                "{\"Relay\":1,\"State\":0,\"Activity\":\"Yes\",\"Zone\":-330}", false);
        String payload = "{\"Relay\":1,\"State\":1,\"Fault\":false,\"Time\":\"15-09-2026 10:00:00\",\"RSSI_R\":-50,\"Vbat\":3.3,\"Vacc\":5.0}";
        processor.process("7000002/EVENT_R1", payload, false);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(telemetryService).onRelayEvent(
                eq(7000002L), eq(1), captor.capture(), eq(1),
                eq(false), eq(-50), eq(3.3f), eq(5.0f));
        assertEquals(Instant.parse("2026-09-15T04:30:00Z"), captor.getValue());
    }

    @Test
    @DisplayName("EVENT_R time for defaulted device is parsed as Moscow")
    void relayEventTimeDefaultMoscow() {
        String payload = "{\"Relay\":1,\"State\":0,\"Fault\":false,\"Time\":\"15-09-2026 10:00:00\",\"RSSI_R\":-50,\"Vbat\":3.3,\"Vacc\":5.0}";
        processor.process("7000003/EVENT_R1", payload, false);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(telemetryService).onRelayEvent(
                eq(7000003L), eq(1), captor.capture(), eq(0),
                eq(false), eq(-50), eq(3.3f), eq(5.0f));
        assertEquals(Instant.parse("2026-09-15T07:00:00Z"), captor.getValue());
    }

    @Test
    @DisplayName("VALUES 1.2.x: BatMin в вольтах читается как милливольты")
    void batMinIsVolts() {
        String payload = "{\"version\":\"1.2.6\",\"ID\":14288428,\"Number\":378,"
                + "\"Battery\":3.02,\"BatLoad\":3.01,\"BatMin\":2.5,\"Moisture\":1570,"
                + "\"Temperature\":21.3,\"Humidity\":56,\"Light\":41,\"Sleep\":60,\"Send\":600,"
                + "\"LN\":11,\"LS\":10,\"AtC\":1,\"AtS\":1,\"tR\":14,\"tM\":7,\"RST\":0,"
                + "\"Ch\":1,\"Time\":\"02-10-2026 14:45:41\"}";

        processor.process("14288428/VALUES", payload, false);

        // Подтверждение настроек обязано получить 2500 мВ, иначе оно не сойдётся с payload
        // команды (60;600;11;10;2500) и retained-настройка останется на брокере.
        verify(confirmation).confirmSensorParams(eq(14288428L), eq(60), eq(600),
                eq(11), eq(10), eq(2500));
    }

    @Test
    @DisplayName("VALUES новой прошивки: MIN_V в милливольтах не переводится")
    void minVoltageIsMillivolts() {
        String payload = "{\"ID\":6021076,\"Number\":3,\"Battery\":3.03,\"BatMin\":1.85,"
                + "\"MIN_V\":1850,\"Sleep\":30,\"Send\":120,\"Time\":\"2025-12-23 16:37:54\"}";

        processor.process("6021076/VALUES", payload, false);

        verify(confirmation).confirmSensorParams(eq(6021076L), eq(30), eq(120),
                isNull(), isNull(), eq(1850));
    }
}
