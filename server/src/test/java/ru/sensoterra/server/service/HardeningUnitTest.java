package ru.sensoterra.server.service;

import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests (no Micronaut context) for input hardening in the service layer. */
@DisplayName("Service hardening")
class HardeningUnitTest {

    @Test
    @DisplayName("Names with ';' or line breaks are rejected; device-sourced names are cleaned")
    void commandNames() {
        assertDoesNotThrow(() -> ParamCommandService.requireSafeName("Полив грядки 2"));
        assertDoesNotThrow(() -> ParamCommandService.requireSafeName(null));
        assertThrows(HttpStatusException.class, () -> ParamCommandService.requireSafeName("x;1;2;2099-01-01"));
        assertThrows(HttpStatusException.class, () -> ParamCommandService.requireSafeName("line\nbreak"));
        assertEquals("a b c", ParamCommandService.payloadName("a;b\rc"));
        assertNull(ParamCommandService.payloadName(null));
    }

    @Test
    @DisplayName("Device state store stops tracking new ids at the cap, keeps known ones")
    void stateStoreCap() {
        DeviceStateStore store = new DeviceStateStore();
        for (long id = 1; id <= DeviceStateStore.MAX_TRACKED_DEVICES; id++) {
            store.touch(id);
        }
        store.touch(999_999_999L);
        assertNull(store.getLastSeen(999_999_999L), "new id past the cap is not tracked");
        store.setStatus(1L, "online");
        assertEquals("online", store.getStatus(1L), "known ids keep updating");
    }
}
