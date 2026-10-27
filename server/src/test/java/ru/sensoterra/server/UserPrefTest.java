package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Настройки отображения в БД: у пользователя свои, при входе с другого устройства
 * те же, и у админа свои — он ничего не навязывает другим.
 */
@MicronautTest(transactional = false)
@DisplayName("Настройки отображения устройств")
class UserPrefTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    SensorRepository sensors;

    @Inject
    DeviceRepository devices;

    @Inject
    ru.sensoterra.server.repository.DeviceAccessRepository access;

    @Inject
    ru.sensoterra.server.repository.UserFieldPrefRepository fieldPrefRows;

    @Inject
    ru.sensoterra.server.service.UserPrefService prefService;

    @Inject
    ru.sensoterra.server.service.SensorFieldDefaults defaults;

    @Inject
    ru.sensoterra.server.repository.SensorFieldDefaultRepository defaultRows;

    private String adminToken;
    private String ownerToken;
    private String otherToken;
    private Long blockId;
    private Long sensorChipId;
    /** Настройки принадлежат пользователю, поэтому у каждого теста своя пара аккаунтов. */
    private String ownerName;
    private String otherName;
    private Long otherId;

    /**
     * В тестах Flyway выключен, схему создаёт Hibernate, поэтому таблица дефолтов
     * приходит пустой — а в проде её наполняет V24. Засеваем её каноническими значениями
     * перед каждым тестом: тесты правят дефолты, и следующий должен начать с исходных.
     */
    private void resetTypeZeroDefaults() {
        defaults.save((short) 0, "v0", "Влажность почвы", "");
        defaults.save((short) 0, "v1", "Температура воздуха", "°C");
        defaults.save((short) 0, "v2", "Влажность воздуха", "%");
        defaults.save((short) 0, "v3", "Освещенность", "Lux");
    }

    private void putDefaults(String token, Object body) {
        client.toBlocking().exchange(
                HttpRequest.PUT("/api/prefs/defaults", body)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
    }

    private Map putDefault(String token, short type, String field, String label, String unit) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.PUT("/api/prefs/defaults", Map.of(String.valueOf(type), Map.of(field,
                        Map.of("label", label, "unit", unit))))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return resp.body();
    }

    private int applyDefaults(String token, short type, String field,
                              String oldLabel, String oldUnit, String newLabel, String newUnit) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/prefs/defaults/apply", Map.of(
                        "sensorType", type, "field", field,
                        "oldLabel", oldLabel, "oldUnit", oldUnit,
                        "newLabel", newLabel, "newUnit", newUnit))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return ((Number) resp.body().get("updated")).intValue();
    }

    private String tokenFor(String username) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return (String) resp.body().get("token");
    }

    private void ensureAdmin() {
        if (adminToken != null) {
            return;
        }
        Map<String, String> creds = Map.of("username", "admin", "password", "admin-pass-123");
        try {
            adminToken = tokenForAdmin(creds);
        } catch (HttpClientResponseException e) {
            adminToken = (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        }
    }

    private String tokenForAdmin(Map<String, String> creds) {
        return (String) client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", creds)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("token");
    }

    private Long ensureUser(String username) {
        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        for (Map u : list.body()) {
            if (username.equals(u.get("username"))) {
                return ((Number) u.get("id")).longValue();
            }
        }
        return ((Number) client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("id")).longValue();
    }

    private void assign(Long userId, Map<String, Object> request) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + userId + "/devices", request)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @BeforeEach
    void setUp() {
        ensureAdmin();
        resetTypeZeroDefaults();
        long suffix = Math.abs((long) java.util.UUID.randomUUID().getLeastSignificantBits() % 1000000);
        ownerName = "prefo" + suffix;
        otherName = "prefx" + suffix;
        Long ownerId = ensureUser(ownerName);
        otherId = ensureUser(otherName);
        blockId = 970000L + suffix;
        sensorChipId = 980000L + suffix;
        assign(ownerId, Map.of("chipId", blockId, "deviceType", "BLOCK"));
        assign(ownerId, Map.of("chipId", sensorChipId, "deviceType", "INDIVIDUAL_SENSOR"));
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment", Map.of("unlimited", true))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        ownerToken = tokenFor(ownerName);
        otherToken = tokenFor(otherName);
    }

    @SuppressWarnings("unchecked")
    private Map prefs(String token) {
        return client.toBlocking().exchange(
                HttpRequest.GET("/api/prefs").header("Authorization", "Bearer " + token),
                Argument.of(Map.class)).body();
    }

    @SuppressWarnings("unchecked")
    private Map patch(String token, Map body) {
        return client.toBlocking().exchange(
                HttpRequest.PUT("/api/prefs", body)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body();
    }

    @SuppressWarnings("unchecked")
    private Map devicePref(Map prefs, Long chipId) {
        Map found = (Map) ((Map<String, Object>) prefs.get("devices")).get(String.valueOf(chipId));
        return found == null ? Map.of() : found;
    }

    @SuppressWarnings("unchecked")
    private Map fieldPref(Map prefs, String type) {
        Map found = (Map) ((Map<String, Object>) prefs.get("fields")).get(type);
        return found == null ? Map.of() : found;
    }

    /** Секция defaults из GET /api/prefs. */
    @SuppressWarnings("unchecked")
    private Map defaultPref(Map prefs, String type) {
        return defaultsSection((Map<String, Object>) prefs.get("defaults"), type);
    }

    /**
     * Ячейки одного типа из карты дефолтов. Ответ PUT /api/prefs/defaults и есть эта карта
     * целиком, без секции defaults, поэтому хелпер принимает её отдельно.
     */
    private Map defaultsSection(Map all, String type) {
        Map found = all == null ? null : (Map) all.get(type);
        return found == null ? Map.of() : found;
    }

    @SuppressWarnings("unchecked")
    private Map sensorPref(Map prefs, Long chipId) {
        Map found = (Map) ((Map<String, Object>) prefs.get("sensors")).get(String.valueOf(chipId));
        return found == null ? Map.of() : found;
    }

    @Test
    @DisplayName("Пустые настройки: у блока нет строки, но в ответе он не появляется")
    void emptyByDefault() {
        Map prefs = prefs(ownerToken);

        assertTrue(((Map) prefs.get("devices")).isEmpty());
        assertTrue(((Map) prefs.get("sensors")).isEmpty());
    }

    @Test
    @DisplayName("Vacc скрывается включительно и привязан к конкретному блоку")
    void vaccIsPerDevice() {
        Map after = patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("hideVacc", false))));
        assertEquals(Boolean.FALSE, devicePref(after, blockId).get("hideVacc"));

        // Второй блок настройку не наследует: у него Vacc остаётся скрыт (дефолт).
        long otherBlock = blockId + 1;
        assign(ensureUser(ownerName), Map.of("chipId", otherBlock, "deviceType", "BLOCK"));
        Map prefs = prefs(ownerToken);
        assertFalse(devicePref(prefs, otherBlock).containsKey("hideVacc"));
    }

    @Test
    @DisplayName("Обновление частичное: незаданные поля не затираются")
    void patchIsMerge() {
        patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("hideVacc", false, "name", "Топка"))));
        Map after = patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Дом"))));
        assertEquals("Дом", devicePref(after, blockId).get("name"));
        assertEquals(Boolean.FALSE, devicePref(after, blockId).get("hideVacc"),
                "hideVacc в запросе не было — значит остаётся прежним");
    }

    @Test
    @DisplayName("Настройки не показываются другим пользователям того же блока")
    void prefsArePrivate() {
        assign(otherId, Map.of("chipId", blockId, "deviceType", "BLOCK"));
        patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Мой алиас"))));
        assertTrue(devicePref(prefs(otherToken), blockId).isEmpty());
    }

    @Test
    @DisplayName("Скрытые каналы и границы оси хранятся по конкретному датчику")
    void sensorFieldsPerChip() {
        Map after = patch(ownerToken, Map.of("sensors", Map.of(
                String.valueOf(sensorChipId),
                Map.of("hiddenFields", List.of("v3"), "yRange", Map.of("v0", Map.of("min", 10, "max", 90))))));
        Map sensor = sensorPref(after, sensorChipId);
        assertEquals(List.of("v3"), sensor.get("hiddenFields"));
        @SuppressWarnings("unchecked")
        Map<String, Map> y = (Map<String, Map>) sensor.get("yRange");
        assertEquals(10, ((Number) y.get("v0").get("min")).intValue());
        assertEquals(90, ((Number) y.get("v0").get("max")).intValue());

        // Список скрытых приходит обратно после перезагрузки страницы.
        Map reloaded = sensorPref(prefs(ownerToken), sensorChipId);
        assertEquals(List.of("v3"), reloaded.get("hiddenFields"));
    }

    @Test
    @DisplayName("Границы оси по каналам обновляются независимо")
    @SuppressWarnings("unchecked")
    void yRangePatchesMergePerField() {
        String sensor = String.valueOf(sensorChipId);
        patch(ownerToken, Map.of("sensors", Map.of(sensor,
                Map.of("yRange", Map.of("v0", Map.of("min", 10, "max", 90))))));
        Map after = patch(ownerToken, Map.of("sensors", Map.of(sensor,
                Map.of("yRange", Map.of("v1", Map.of("min", -5, "max", 5))))));
        Map<String, Map> y = (Map<String, Map>) sensorPref(after, sensorChipId).get("yRange");
        assertEquals(10, ((Number) y.get("v0").get("min")).intValue());
        assertEquals(90, ((Number) y.get("v0").get("max")).intValue());
        assertEquals(-5, ((Number) y.get("v1").get("min")).intValue());
        assertEquals(5, ((Number) y.get("v1").get("max")).intValue());

        // «Авто» для одного канала не должно стирать границы остальных.
        Map cleared = patch(ownerToken, Map.of("sensors", Map.of(sensor,
                Map.of("yRange", Map.of("v1", Map.of())))));
        y = (Map<String, Map>) sensorPref(cleared, sensorChipId).get("yRange");
        assertFalse(y.containsKey("v1"));
        assertEquals(10, ((Number) y.get("v0").get("min")).intValue());
        assertEquals(90, ((Number) y.get("v0").get("max")).intValue());

        // Односторонняя граница должна переживать сохранение и повторное чтение.
        patch(ownerToken, Map.of("sensors", Map.of(sensor,
                Map.of("yRange", Map.of("v2", Map.of("min", 3))))));
        y = (Map<String, Map>) sensorPref(prefs(ownerToken), sensorChipId).get("yRange");
        assertEquals(3, ((Number) y.get("v2").get("min")).intValue());
        assertNull(y.get("v2").get("max"));
        assertEquals(10, ((Number) y.get("v0").get("min")).intValue());

        patch(ownerToken, Map.of("sensors", Map.of(sensor,
                Map.of("yRange", Map.of("v3", Map.of("max", 42))))));
        y = (Map<String, Map>) sensorPref(prefs(ownerToken), sensorChipId).get("yRange");
        assertNull(y.get("v3").get("min"));
        assertEquals(42, ((Number) y.get("v3").get("max")).intValue());
        assertEquals(10, ((Number) y.get("v0").get("min")).intValue());
    }

    @Test
    @DisplayName("Настройки чужого устройства недоступны")
    void cannotTouchForeignDevice() {
        long foreign = blockId + 7;
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                patch(ownerToken, Map.of("devices", Map.of(String.valueOf(foreign), Map.of("name", "не моё")))));
        assertEquals(404, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Админ настраивает интерфейс сам, его настройки не достаются пользователю")
    void adminPrefsAreOwnOnly() {
        Map adminPrefs = patch(adminToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Админский блок", "hideVacc", false))));
        assertEquals("Админский блок", devicePref(adminPrefs, blockId).get("name"));
        assertTrue(devicePref(prefs(ownerToken), blockId).isEmpty(),
                "пользователь не должен увидеть настройки админа");
    }

    @Test
    @DisplayName("При принятии приглашения настройки пригласившего переходят новичку")
    void prefsTransferOnInvite() {
        // Приглашение выдаёт управляющий (owner), он же настраивает свой блок.
        patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Баня", "hideVacc", false))));

        String inviteToken = (String) client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/invites", Map.of())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("token");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + inviteToken + "/accept", Map.of())
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON));

        Map inherited = devicePref(prefs(otherToken), blockId);
        assertEquals("Баня", inherited.get("name"));
        assertEquals(Boolean.FALSE, inherited.get("hideVacc"));
    }

    @Test
    @DisplayName("Свои настройки принявшего приглашение не затираются переносом")
    void existingPrefsWin() {
        patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Баня"))));

        acceptInvite();
        patch(otherToken, Map.of("devices", Map.of(
                String.valueOf(blockId), Map.of("name", "Моё"))));

        // Доступ отбирают и зовут снова: старые настройки остались у пользователя при себе,
        // и повторный перенос не должен их затирать.
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + blockId + "/access/" + otherId)
                        .header("Authorization", "Bearer " + adminToken));
        acceptInvite();

        assertEquals("Моё", devicePref(prefs(otherToken), blockId).get("name"));
    }

    private void acceptInvite() {
        String token = (String) client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/invites", Map.of())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("token");
        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Скрытие канала у датчика, который в каталоге есть и отдельно, и в блоке")
    void hiddenFieldForChipPresentTwiceInCatalog() {
        // Живой случай: чип 6021076 есть в каталоге дважды — своим устройством и датчиком
        // слота 1 блока 13034338. Пользователь имеет доступ только к блоку, поэтому
        // настройка датчика обязана проходить по строке блока, а не по «своей» строке.
        Long dupChipId = sensorChipId + 1;
        Device standalone = new Device();
        standalone.setChipId(dupChipId);
        standalone.setDeviceType("BLOCK_SENSOR");
        devices.save(standalone);
        Sensor alone = new Sensor();
        alone.setSensorChipId(dupChipId);
        alone.setDeviceId(dupChipId);
        sensors.save(alone);
        Sensor inBlock = new Sensor();
        inBlock.setSensorChipId(dupChipId);
        inBlock.setDeviceId(blockId);
        inBlock.setSensorNumber((short) 1);
        inBlock.setSensorType((short) 0);
        sensors.save(inBlock);

        patch(ownerToken, Map.of("sensors", Map.of(String.valueOf(dupChipId),
                Map.of("hiddenFields", List.of("v3")))));

        Map prefs = prefs(ownerToken);
        assertEquals(List.of("v3"), sensorPref(prefs, dupChipId).get("hiddenFields"),
                "канал должен скрываться у того, кому виден блок с этим датчиком");
    }

    @Test
    @DisplayName("У типа 0 после переезда прежние подписи, у остальных типов пусто")
    void typeZeroSeeded() {
        Map prefs = prefs(ownerToken);
        Map type0 = fieldPref(prefs, "0");
        assertEquals("Влажность почвы", ((Map) type0.get("v0")).get("label"));
        assertEquals("°C", ((Map) type0.get("v1")).get("unit"));
        assertEquals("Влажность воздуха", ((Map) type0.get("v2")).get("label"));
        assertEquals("Lux", ((Map) type0.get("v3")).get("unit"));

        // Других типов в базе нет — значит, и подписей у них нет.
        for (Object key : ((Map) prefs.get("fields")).keySet()) {
            assertEquals("0", String.valueOf(key), "подсеивать нужно только тип 0");
        }
    }

    @Test
    @DisplayName("Подписи канала сохраняются по типу датчика и не затирают соседние")
    void fieldLabelsPerType() {
        Map after = patch(ownerToken, Map.of("fields", Map.of("5",
                Map.of("v0", Map.of("label", "Освещенность", "unit", "lux")))));
        Map type5 = fieldPref(after, "5");
        assertEquals("Освещенность", ((Map) type5.get("v0")).get("label"));
        assertEquals("lux", ((Map) type5.get("v0")).get("unit"));
        // Тип 0 на месте — правка другого типа его не тронула.
        assertEquals("Влажность почвы",
                ((Map) fieldPref(after, "0").get("v0")).get("label"));

        Map reread = (Map) prefs(ownerToken).get("fields");
        assertEquals("Освещенность", ((Map) ((Map) reread.get("5")).get("v0")).get("label"));
    }

    @Test
    @DisplayName("Пустая подпись — это «показать как есть», а не «вернуть умолчание»")
    void emptyLabelMeansBlank() {
        patch(ownerToken, Map.of("fields", Map.of("5", Map.of("v0", Map.of("label", "Вода")))));
        Map after = patch(ownerToken, Map.of("fields", Map.of("5", Map.of("v0", Map.of("label", "")))));
        Map type5 = fieldPref(after, "5");
        assertNull(((Map) type5.get("v0")).get("label"), "пустое значение должно сохраниться как null");
    }

    @Test
    @DisplayName("Подписи не требуют доступа к блоку: это личный словарик")
    void fieldLabelsNeedNoDeviceAccess() {
        Map after = patch(ownerToken, Map.of("fields", Map.of("7",
                Map.of("v1", Map.of("label", "Температура")))));
        assertEquals("Температура",
                ((Map) fieldPref(after, "7").get("v1")).get("label"));
    }

    @Test
    @DisplayName("Дефолты подписей приходят с сервера и только для типов, которые сервер знает")
    void defaultsComeFromServer() {
        Map prefs = prefs(ownerToken);
        Map type0 = defaultPref(prefs, "0");
        assertEquals("Влажность почвы", ((Map) type0.get("v0")).get("label"));
        assertEquals("°C", ((Map) type0.get("v1")).get("unit"));
        assertEquals("Влажность воздуха", ((Map) type0.get("v2")).get("label"));
        assertEquals("Lux", ((Map) type0.get("v3")).get("unit"));

        // Типа 5 в коде дефолтов нет, и сервер не имеет права сочинить его на лету.
        assertNull(defaultPref(prefs, "5").get("v0"), "у типа без дефолтов сервер не присылает подписи");
        assertNull(((Map<String, Object>) prefs.get("defaults")).get("5"),
                "в defaults не должно быть типа, которого нет в коде");
    }

    @Test
    @DisplayName("Очищенная подпись не превращается обратно в дефолт: клиенту видно и то, и другое")
    void clearedLabelKeepsDefaultSeparate() {
        patch(ownerToken, Map.of("fields", Map.of("0", Map.of("v0", Map.of("label", "")))));
        Map prefs = prefs(ownerToken);
        assertNull(((Map) fieldPref(prefs, "0").get("v0")).get("label"),
                "личная ячейка остаётся пустой — пользователь очистил её сам");
        assertEquals("Влажность почвы", ((Map) defaultPref(prefs, "0").get("v0")).get("label"),
                "дефолт остаётся на месте, решение «показать v0» принимает клиент");
    }

    @Test
    @DisplayName("Повторный сид дефолтов не падает на первичном ключе и не плодит строки")
    void seedDefaultsIsIdempotent() {
        long userId = ensureUser(ownerName);
        long before = fieldPrefRows.findByUserId(userId).size();

        prefService.seedDefaults(userId);
        prefService.seedDefaults(userId);

        long after = fieldPrefRows.findByUserId(userId).size();
        assertEquals(before, after, "повторный сид не должен ни падать, ни добавлять строки");
        assertEquals(4, after, "у нового пользователя четыре подписи типа 0");
    }

    @Test
    @DisplayName("Дефолты правит админ, обычный пользователь — нет")
    void onlyAdminEditsDefaults() {
        Map before = prefs(ownerToken);
        assertEquals("Влажность почвы", ((Map) defaultPref(before, "0").get("v0")).get("label"));

        assertThrows(HttpClientResponseException.class, () -> putDefault(ownerToken, (short) 0, "v0",
                        "Подпись обычного пользователя", "шт"),
                "пользователь не должен менять общие значения по умолчанию");

        assertEquals("Влажность почвы", ((Map) defaultPref(prefs(ownerToken), "0").get("v0")).get("label"),
                "после отказа пользователя дефолт не должен измениться");

        Map after = putDefault(adminToken, (short) 0, "v0", "Влажность грунта", "%");
        assertEquals("Влажность грунта", ((Map) defaultsSection(after, "0").get("v0")).get("label"));
        assertEquals("%", ((Map) defaultsSection(after, "0").get("v0")).get("unit"));
        assertEquals("Влажность грунта",
                ((Map) defaultPref(prefs(ownerToken), "0").get("v0")).get("label"),
                "новый дефолт должен быть виден и обычному пользователю");
        // Личная подпись пользователя при правке дефолта не трогается.
        assertEquals("Влажность почвы", ((Map) fieldPref(prefs(ownerToken), "0").get("v0")).get("label"));
    }

    @Test
    @DisplayName("Правка дефолта — merge: соседние ячейки и типы не затираются")
    void defaultUpdateIsMerge() {
        putDefault(adminToken, (short) 0, "v1", "Температура на воздухе", "°F");
        Map after = putDefault(adminToken, (short) 0, "v0", "Влажность грунта", "%");
        assertEquals("Температура на воздухе", ((Map) defaultsSection(after, "0").get("v1")).get("label"));
        assertEquals("°F", ((Map) defaultsSection(after, "0").get("v1")).get("unit"));
        assertEquals("Влажность грунта", ((Map) defaultsSection(after, "0").get("v0")).get("label"));
    }

    @Test
    @DisplayName("Новый пользователь получает дефолт, актуальный на момент регистрации")
    void newUserGetsCurrentDefault() {
        putDefault(adminToken, (short) 0, "v0", "Влажность грунта", "%");
        String freshName = ownerName + "_fresh";
        long freshId = ensureUser(freshName);
        Map fields = (Map) prefs(tokenFor(freshName)).get("fields");
        assertEquals("Влажность грунта", ((Map) ((Map) fields.get("0")).get("v0")).get("label"));
        assertEquals("%", ((Map) ((Map) fields.get("0")).get("v0")).get("unit"));
        assertTrue(fieldPrefRows.findByUserId(freshId).size() >= 4);
    }

    @Test
    @DisplayName("Раскатка дефолта трогает только тех, кто подпись не менял руками")
    void applyDefaultsSkipsHandEdited() {
        // Отдельный тип: тестовая база общая на весь прогон, и строки типа 0 остаются от
        // других тестов. Со своим типом и своими пользователями счёт получается точным.
        short scratch = 42;
        dropScratchType(scratch);
        putDefault(adminToken, scratch, "v0", "Старый дефолт", "");

        String quiet = ownerName + "_quiet";
        String manual = otherName + "_manual";
        ensureUser(quiet);
        ensureUser(manual);
        patch(tokenFor(manual), Map.of("fields", Map.of(String.valueOf(scratch),
                Map.of("v0", Map.of("label", "Моя подпись", "unit", "шт")))));

        assertEquals("Старый дефолт",
                ((Map) fieldPref(prefs(tokenFor(quiet)), String.valueOf(scratch)).get("v0")).get("label"),
                "новому пользователю достался дефолт, заведённый до его регистрации");

        int updated = applyDefaults(adminToken, scratch, "v0",
                "Старый дефолт", "", "Новый дефолт", "%");
        assertEquals(1, updated, "обновиться должна ровно одна строка — та, где стоял старый дефолт");

        Map quietFields = fieldPref(prefs(tokenFor(quiet)), String.valueOf(scratch));
        assertEquals("Новый дефолт", ((Map) quietFields.get("v0")).get("label"));
        assertEquals("%", ((Map) quietFields.get("v0")).get("unit"));

        Map manualFields = fieldPref(prefs(tokenFor(manual)), String.valueOf(scratch));
        assertEquals("Моя подпись", ((Map) manualFields.get("v0")).get("label"),
                "подпись, выбранная руками, раскатка не должна затирать");
        assertEquals("шт", ((Map) manualFields.get("v0")).get("unit"));

        dropScratchType(scratch);
    }

    /** Сносит строки тестового типа и в дефолтах, и в личных настройках. */
    private void dropScratchType(short sensorType) {
        for (ru.sensoterra.server.entity.UserFieldPref row : fieldPrefRows.findBySensorType(sensorType)) {
            fieldPrefRows.delete(row);
        }
        defaultRows.findBySensorTypeOrderByField(sensorType)
                .forEach(row -> defaultRows.delete(row));
    }

    @Test
    @DisplayName("Настройки не требуют оплаты блока")
    void prefsIgnorePayment() {
        long unpaid = blockId + 11;
        assign(ensureUser(ownerName), Map.of("chipId", unpaid, "deviceType", "BLOCK"));
        Map after = patch(ownerToken, Map.of("devices", Map.of(
                String.valueOf(unpaid), Map.of("hideVacc", false))));
        assertEquals(Boolean.FALSE, devicePref(after, unpaid).get("hideVacc"));
    }
}