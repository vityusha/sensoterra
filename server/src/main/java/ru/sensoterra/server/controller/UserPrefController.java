package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.UserPrefService;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

/**
 * Настройки отображения, привязанные к блокам и датчикам: алиас имени, скрытие Vacc,
 * скрытые каналы и границы оси Y. В отличие от оповещений и подсказок графиков, они
 * хранятся в БД, чтобы не настраивать интерфейс заново на каждом устройстве входа.
 */
@Controller("/api/prefs")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class UserPrefController {

    private final UserPrefService prefs;
    private final DeviceAccessService access;

    public UserPrefController(UserPrefService prefs, DeviceAccessService access) {
        this.prefs = prefs;
        this.access = access;
    }

    @Get
    public UserPrefService.Prefs get() {
        return prefs.get(access.currentUser().getId());
    }

    /** Merge: приходят только изменяемые поля, остальное не трогаем. */
    @Put
    public UserPrefService.Prefs update(@Body UserPrefService.PrefsPatch patch) {
        AppUser user = access.currentUser();
        return prefs.update(user.getId(), patch);
    }
}
