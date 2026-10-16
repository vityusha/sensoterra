package ru.sensoterra.server.controller;

import ru.sensoterra.server.service.SensorCalibrationService;
import ru.sensoterra.server.service.UserPrefService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.security.annotation.Secured;

import java.util.Map;

/**
 * Правка дефолтных подписей каналов и единиц — те значения, которые пользователь видит,
 * пока не завёл свои.
 *
 * Отдельный контроллер с {@code @Secured("ADMIN")}, а не ветка в UserPrefController: там
 * для всех IS_AUTHENTICATED, и право менять общие значения не должно получаться вместе с
 * правом менять свои.
 *
 * Личные подписи пользователей при правке дефолта не меняются: у кого-то подпись уже
 * выбрана руками, и подменять её молча нельзя. Чтобы обновить её у тех, кто ничего не
 * выбирал, есть отдельный apply — с явным «от чего» и «к чему», потому что админ жмёт
 * эту кнопку отдельно от сохранения и что было на экране, сервер не знает.
 */
@Controller("/api/prefs/defaults")
@Secured("ADMIN")
public class PrefDefaultAdminController {

    private final UserPrefService prefs;
    private final SensorCalibrationService calibrations;

    public PrefDefaultAdminController(UserPrefService prefs, SensorCalibrationService calibrations) {
        this.prefs = prefs;
        this.calibrations = calibrations;
    }

    /** Merge: приходят только изменяемые ячейки. */
    @Put
    public Map<String, Map<String, UserPrefService.FieldPref>> update(
            @Body Map<String, Map<String, UserPrefService.FieldPref>> patch) {
        return prefs.updateDefaults(patch);
    }

    /** Дефолтные опорные точки калибровки влажности по типам датчиков. */
    @Get("/calibration-defaults")
    public Map<String, SensorCalibrationService.Values> calibrationDefaults() {
        return calibrations.defaultsAll();
    }

    @Put("/calibration-defaults")
    public Map<String, SensorCalibrationService.Values> updateCalibrationDefaults(
            @Body Map<String, SensorCalibrationService.Values> patch) {
        return calibrations.saveDefaults(patch);
    }

    @Post("/apply")
    public HttpResponse<ApplyResult> apply(@Body ApplyRequest request) {
        int updated = prefs.applyDefaultsToUsers(
                request.sensorType(), request.field(),
                request.oldLabel(), request.oldUnit(),
                request.newLabel(), request.newUnit());
        return HttpResponse.ok(new ApplyResult(updated,
                "Обновлено подписей: " + updated
                        + ". У кого подпись была изменена вручную, она не тронута."));
    }

    /**
     * Что во что раскатывать. oldLabel/oldUnit — то, что было на экране до правки,
     * newLabel/newUnit — то, что админ хочет видеть у пользователей.
     */
    @Serdeable
    public record ApplyRequest(short sensorType, String field,
                              String oldLabel, String oldUnit,
                              String newLabel, String newUnit) {
    }

    @Serdeable
    public record ApplyResult(int updated, String message) {
    }
}