package ru.sensoterra.server;

import ru.sensoterra.server.service.DeviceZoneService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@DisplayName("Device timezone service")
class DeviceZoneServiceTest {

    @Inject
    DeviceZoneService deviceZoneService;

    @Test
    @DisplayName("Unknown device falls back to Moscow (+3 hrs)")
    void defaultMoscow() {
        assertEquals(180, deviceZoneService.offsetFor(7777770L));
        assertEquals(ZoneOffset.ofHours(3), deviceZoneService.zoneFor(7777770L));
    }

    @Test
    @DisplayName("Firmware Zone -180 (Moscow) is stored inverted as +180")
    void storeMoscow() {
        deviceZoneService.updateZone(7777771L, -180);
        assertEquals(180, deviceZoneService.offsetFor(7777771L));
        assertEquals(ZoneOffset.ofHours(3), deviceZoneService.zoneFor(7777771L));
    }

    @Test
    @DisplayName("Firmware Zone -330 maps to UTC+05:30")
    void storeUtc530() {
        deviceZoneService.updateZone(7777772L, -330);
        assertEquals(330, deviceZoneService.offsetFor(7777772L));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), deviceZoneService.zoneFor(7777772L));
    }

    @Test
    @DisplayName("Firmware Zone 0 maps to UTC")
    void storeUtc() {
        deviceZoneService.updateZone(7777773L, 0);
        assertEquals(0, deviceZoneService.offsetFor(7777773L));
        assertEquals(ZoneOffset.UTC, deviceZoneService.zoneFor(7777773L));
    }

    @Test
    @DisplayName("Out-of-range Zone from a device is ignored and does not break the device")
    void outOfRangeZoneIgnored() {
        deviceZoneService.updateZone(7777775L, -180);
        // Unbounded before: 100000 minutes made ZoneOffset.ofTotalSeconds throw on every message.
        deviceZoneService.updateZone(7777775L, 100000);
        deviceZoneService.updateZone(7777775L, -100000);
        assertEquals(180, deviceZoneService.offsetFor(7777775L));
        assertEquals(ZoneOffset.ofHours(3), deviceZoneService.zoneFor(7777775L));
    }

    @Test
    @DisplayName("remove keeps persisted value readable")
    void removeDoesNotBreak() {
        deviceZoneService.updateZone(7777774L, -330);
        deviceZoneService.remove(7777774L);
        assertEquals(330, deviceZoneService.offsetFor(7777774L));
    }
}