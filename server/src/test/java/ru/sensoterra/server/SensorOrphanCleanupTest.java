package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.TelemetryService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@DisplayName("Orphan sensor rows cleanup on SENSORS update")
class SensorOrphanCleanupTest {

    @Inject
    TelemetryService telemetryService;
    @Inject
    DeviceRepository deviceRepository;
    @Inject
    SensorRepository sensorRepository;

    private Long block(Long chipId) {
        Device d = new Device();
        d.setChipId(chipId);
        d.setDeviceType("BLOCK");
        return deviceRepository.save(d).getChipId();
    }

    private Long addSensor(Long deviceId, Long chipId) {
        Sensor s = new Sensor();
        s.setDeviceId(deviceId);
        s.setSensorChipId(chipId);
        s.setName("Test " + chipId);
        return sensorRepository.save(s).getId();
    }

    @Test
    @DisplayName("Removing a slot deletes the orphaned sensor row for that block")
    void unregisteredSlotDeletesOrphan() {
        Long deviceId = block(7777888L);
        addSensor(deviceId, 6021076L);

        Map<Integer, Long> slots = new LinkedHashMap<>();
        slots.put(1, 6021076L);
        slots.put(2, 0L);
        telemetryService.updateRegisteredSensors(deviceId, slots);
        assertTrue(sensorRepository.findByDeviceIdAndSensorChipId(deviceId, 6021076L).isPresent());

        slots.clear();
        slots.put(1, 0L);
        slots.put(2, 0L);
        telemetryService.updateRegisteredSensors(deviceId, slots);
        assertTrue(sensorRepository.findByDeviceIdAndSensorChipId(deviceId, 6021076L).isEmpty());
    }

    @Test
    @DisplayName("Registered sensor rows are kept even if another chip is removed")
    void keepsStillRegisteredRows() {
        Long deviceId = block(7777889L);
        addSensor(deviceId, 6021076L);
        addSensor(deviceId, 13035859L);

        Map<Integer, Long> slots = new LinkedHashMap<>();
        slots.put(1, 6021076L);
        slots.put(2, 0L);
        telemetryService.updateRegisteredSensors(deviceId, slots);

        assertTrue(sensorRepository.findByDeviceIdAndSensorChipId(deviceId, 6021076L).isPresent());
        assertTrue(sensorRepository.findByDeviceIdAndSensorChipId(deviceId, 13035859L).isEmpty());
    }

    @Test
    @DisplayName("Registration without a sensor row creates one with the slot number")
    void registrationCreatesSensorRow() {
        Long deviceId = block(7777900L);
        Map<Integer, Long> slots = new LinkedHashMap<>();
        slots.put(1, 6021076L);
        slots.put(2, 0L);
        telemetryService.updateRegisteredSensors(deviceId, slots);

        var s = sensorRepository.findByDeviceIdAndSensorChipId(deviceId, 6021076L);
        assertTrue(s.isPresent());
        assertEquals((short) 1, s.get().getSensorNumber());
    }
}