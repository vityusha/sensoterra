package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorFieldDefault;
import ru.sensoterra.server.entity.UserDevicePref;
import ru.sensoterra.server.entity.UserFieldPref;
import ru.sensoterra.server.entity.UserSensorPref;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.repository.UserDevicePrefRepository;
import ru.sensoterra.server.repository.UserFieldPrefRepository;
import ru.sensoterra.server.repository.UserSensorPrefRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Настройки отображения интерфейса, хранящиеся в БД.
 *
 * Тут живут только те настройки, которые относятся к конкретному блоку или датчику:
 * алиас имени, скрытие Vacc у блока, скрытые каналы и границы оси Y у датчика.
 * Всё, что не зависит от устройства (оповещения, подсказки графиков), остаётся
 * в браузере.
 *
 * Настройки принадлежат пользователю. Админ настраивает свой интерфейс своими же
 * строками и ничего не навязывает другим: чужие строки он не читает и не пишет.
 */
@Singleton
public class UserPrefService {

    private final UserDevicePrefRepository devicePrefs;
    private final UserSensorPrefRepository sensorPrefs;
    private final UserFieldPrefRepository fieldPrefs;
    private final SensorRepository sensorRepository;
    private final DeviceAccessService access;
    private final SensorFieldDefaults defaults;

    public UserPrefService(UserDevicePrefRepository devicePrefs,
                           UserSensorPrefRepository sensorPrefs,
                           UserFieldPrefRepository fieldPrefs,
                           SensorRepository sensorRepository,
                           DeviceAccessService access,
                           SensorFieldDefaults defaults) {
        this.devicePrefs = devicePrefs;
        this.sensorPrefs = sensorPrefs;
        this.fieldPrefs = fieldPrefs;
        this.sensorRepository = sensorRepository;
        this.access = access;
        this.defaults = defaults;
    }

    /**
     * Заводит новому пользователю подписи каналов из {@link SensorFieldDefaults} — тех
     * же, что показывались в браузере по умолчанию. Типы, для которых дефолтов нет,
     * остаются пустыми: пользователь увидит сырые ключи каналов и подпишет их сам.
     * Вызывается при создании аккаунта, существующим пользователям подсеяла миграция.
     *
     * Идемпотентна: повторный вызов не пытается вставить строку заново и не падает на
     * первичном ключе (user_id, sensor_type, field).
     */
    @Transactional
    public void seedDefaults(Long userId) {
        Set<String> existing = new HashSet<>();
        for (UserFieldPref p : fieldPrefs.findByUserId(userId)) {
            existing.add(p.getSensorType() + "/" + p.getField());
        }
        for (Short sensorType : defaults.types()) {
            for (SensorFieldDefault d : defaults.forType(sensorType)) {
                if (!existing.add(sensorType + "/" + d.getField())) {
                    continue;
                }
                UserFieldPref pref = new UserFieldPref();
                pref.setUserId(userId);
                pref.setSensorType(sensorType);
                pref.setField(d.getField());
                pref.setLabel(d.getLabel());
                pref.setUnit(d.getUnit());
                fieldPrefs.save(pref);
            }
        }
    }

    public Prefs get(Long userId) {
        Map<String, DevicePref> devices = new LinkedHashMap<>();
        for (UserDevicePref p : devicePrefs.findByUserId(userId)) {
            devices.put(String.valueOf(p.getChipId()),
                    new DevicePref(p.isHideVacc(), p.getName()));
        }
        Map<String, SensorPref> sensors = new LinkedHashMap<>();
        for (UserSensorPref p : sensorPrefs.findByUserId(userId)) {
            sensors.put(String.valueOf(p.getSensorChipId()),
                    new SensorPref(splitHidden(p.getHiddenFields()), parseYRange(p.getYRange())));
        }
        Map<String, Map<String, FieldPref>> fields = new LinkedHashMap<>();
        for (UserFieldPref p : fieldPrefs.findByUserId(userId)) {
            fields.computeIfAbsent(String.valueOf(p.getSensorType()), k -> new LinkedHashMap<>())
                    .put(p.getField(), new FieldPref(trimToNull(p.getLabel()), trimToNull(p.getUnit())));
        }
        return new Prefs(devices, sensors, fields, defaultFields());
    }

    /**
     * Дефолты в той же форме, что и личные подписи, — клиенту не нужно знать, откуда они
     * взялись. Отдаём всем: это не чьи-то настройки, а значения по умолчанию для
     * канала, подписи которого у пользователя ещё нет.
     */
    /**
     * Правка дефолтов админом. Merge: приходят только изменяемые ячейки, остальные
     * строки не трогаем. Возвращает дефолты в том же виде, в каком их видит клиент.
     *
     * Личные подписи пользователей при этом не меняются: у кого-то подпись уже стоит,
     * и подменять её молча нельзя. Чтобы поменять её у тех, кто ничего не выбирал, есть
     * {@link #applyDefaultsToUsers}.
     */
    public Map<String, Map<String, FieldPref>> updateDefaults(Map<String, Map<String, FieldPref>> patch) {
        if (patch == null) {
            return defaultFields();
        }
        for (Map.Entry<String, Map<String, FieldPref>> perType : patch.entrySet()) {
            short sensorType = parseSensorType(perType.getKey());
            if (perType.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, FieldPref> perField : perType.getValue().entrySet()) {
                FieldPref value = perField.getValue();
                defaults.save(sensorType, perField.getKey(),
                        value == null ? null : trimToNull(value.label()),
                        value == null ? null : trimToNull(value.unit()));
            }
        }
        return defaultFields();
    }

    /**
     * Раскатывает изменённый дефолт тем пользователям, у которых своя подпись этого
     * канала всё ещё равна старому дефолту, — то есть кто её не выбирал сам. Чужие
     * решения не затираются: строка, отличающаяся от старого дефолта хотя бы чем-то
     * (подписью или единицей), остаётся как есть.
     *
     * oldLabel/oldUnit приходят от клиента, потому что админ нажимает кнопку отдельно от
     * сохранения и сервер не обязан помнить, что было показано на экране. Пустая строка
     * и null тут равны: личные подписи хранятся через trimToNull, и «очистил» не должно
     * отличаться от «старый дефолт был пустой».
     *
     * @return сколько строк обновлено.
     */
    @Transactional
    public int applyDefaultsToUsers(short sensorType, String field,
                                    String oldLabel, String oldUnit,
                                    String newLabel, String newUnit) {
        String oldL = trimToNull(oldLabel);
        String oldU = trimToNull(oldUnit);
        String newL = trimToNull(newLabel);
        String newU = trimToNull(newUnit);
        int touched = 0;
        // Строки грузятся и правятся внутри транзакции: у UserFieldPref assigned id,
        // и save() отсоединённой сущности сделал бы INSERT, а не UPDATE.
        for (UserFieldPref row : fieldPrefs.findBySensorTypeAndField(sensorType, field)) {
            if (!Objects.equals(trimToNull(row.getLabel()), oldL)
                    || !Objects.equals(trimToNull(row.getUnit()), oldU)) {
                continue;
            }
            row.setLabel(newL);
            row.setUnit(newU);
            fieldPrefs.save(row);
            touched++;
        }
        return touched;
    }

    /** Тип датчика из ключа секции настроек; мусор приводим к нулю, а не роняем запрос. */
    private short parseSensorType(String raw) {
        try {
            return Short.parseShort(raw.trim());
        } catch (NumberFormatException e) {
            return (short) 0;
        }
    }

    private Map<String, Map<String, FieldPref>> defaultFields() {
        Map<String, Map<String, FieldPref>> out = new LinkedHashMap<>();
        defaults.asMap().forEach((type, byField) -> {
            Map<String, FieldPref> converted = new LinkedHashMap<>();
            // Приводим к тому же виду, что и личные подписи: пустая строка в дефолте и
            // null в строке пользователя должны выглядеть для клиента одинаково.
            byField.forEach((field, d) -> converted.put(field,
                    new FieldPref(trimToNull(d.label()), trimToNull(d.unit()))));
            out.put(type, converted);
        });
        return out;
    }

    /**
     * Частичное обновление: трогаются только те устройства и датчики, что пришли в запросе,
     * и внутри них только явно заданные поля. Клиент шлёт одно поле за раз, поэтому
     * undefined-поля должны означать «не менять», а не «сбросить».
     */
    @Transactional
    public Prefs update(Long userId, PrefsPatch patch) {
        if (patch.devices() != null) {
            patch.devices().forEach((chipId, d) -> applyDevice(userId, parseLong(chipId), d));
        }
        if (patch.sensors() != null) {
            patch.sensors().forEach((chipId, s) -> applySensor(userId, parseLong(chipId), s));
        }
        if (patch.fields() != null) {
            // Подписи каналов относятся к типу датчика, а не к устройству, поэтому
            // проверки доступа к блоку здесь нет: это личный словарик пользователя.
            patch.fields().forEach((type, fields) -> applyField(userId, shortValue(type), fields));
        }
        return get(userId);
    }

    private void applyField(Long userId, Short sensorType, Map<String, FieldPref> fields) {
        if (sensorType == null || fields == null) {
            return;
        }
        for (Map.Entry<String, FieldPref> e : fields.entrySet()) {
            String field = e.getKey() == null ? null : e.getKey().trim();
            FieldPref value = e.getValue();
            if (field == null || field.isEmpty() || value == null) {
                continue;
            }
            Optional<UserFieldPref> existing = fieldPrefs.findByUserIdAndSensorTypeAndField(userId, sensorType, field);
            if (existing.isEmpty() && value.label() == null && value.unit() == null) {
                continue;
            }
            UserFieldPref pref = existing.orElseGet(() -> {
                UserFieldPref fresh = new UserFieldPref();
                fresh.setUserId(userId);
                fresh.setSensorType(sensorType);
                fresh.setField(field);
                return fresh;
            });
            if (value.label() != null) {
                pref.setLabel(trimToNull(value.label()));
            }
            if (value.unit() != null) {
                pref.setUnit(trimToNull(value.unit()));
            }
            fieldPrefs.save(pref);
        }
    }

    private static Short shortValue(String type) {
        try {
            return type == null ? null : Short.valueOf(type.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyDevice(Long userId, Long chipId, DevicePrefPatch patch) {
        if (chipId == null || patch == null) {
            return;
        }
        if (!access.canView(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        Optional<UserDevicePref> existing = devicePrefs.findByUserIdAndChipId(userId, chipId);
        if (existing.isEmpty() && patch.hideVacc() == null && patch.name() == null) {
            return;
        }
        UserDevicePref pref = existing.orElseGet(() -> {
            UserDevicePref fresh = new UserDevicePref();
            fresh.setUserId(userId);
            fresh.setChipId(chipId);
            return fresh;
        });
        if (patch.hideVacc() != null) {
            pref.setHideVacc(patch.hideVacc());
        }
        if (patch.name() != null) {
            String name = patch.name().trim();
            pref.setName(name.isEmpty() ? null : name);
        }
        devicePrefs.save(pref);
    }

    private void applySensor(Long userId, Long sensorChipId, SensorPrefPatch patch) {
        if (sensorChipId == null || patch == null) {
            return;
        }
        requireVisibleSensor(sensorChipId);
        if (patch.hiddenFields() != null || patch.yRange() != null) {
            // Скрытие каналов и границы оси — уже «настройка», а не отображение:
            // viewer не меняет их ни через интерфейс, ни напрямую через API.
            requireControlSensor(sensorChipId);
        }
        Optional<UserSensorPref> existing = sensorPrefs.findByUserIdAndSensorChipId(userId, sensorChipId);
        if (existing.isEmpty() && patch.hiddenFields() == null && patch.yRange() == null) {
            return;
        }
        UserSensorPref pref = existing.orElseGet(() -> {
            UserSensorPref fresh = new UserSensorPref();
            fresh.setUserId(userId);
            fresh.setSensorChipId(sensorChipId);
            return fresh;
        });
        if (patch.hiddenFields() != null) {
            pref.setHiddenFields(joinHidden(patch.hiddenFields()));
        }
        if (patch.yRange() != null) {
            pref.setYRange(writeYRange(patch.yRange()));
        }
        sensorPrefs.save(pref);
    }

    /**
     * Право управлять датчиком: автономный датчик — он сам, датчик слота — его блок.
     * Принцип тот же, что и в {@link #requireVisibleSensor}, но с правами на запись.
     */
    private void requireControlSensor(Long sensorChipId) {
        if (access.isAdmin()) {
            return;
        }
        for (Sensor sensor : sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(sensorChipId)) {
            if (sensor.getDeviceId() != null && access.canControl(sensor.getDeviceId())) {
                return;
            }
        }
        if (access.canControl(sensorChipId)) {
            return;
        }
        throw new HttpStatusException(HttpStatus.FORBIDDEN, "Read-only access to this device");
    }

    /**
     * Датчик виден, если виден либо сам чип (автономный датчик), либо блок, в слот
     * которого он вставлен. Оплату не проверяем: это настройки интерфейса, а не данные
     * устройства, и закрытый по тарифу блок не должен лишать возможности скрыть лишнее.
     */
    private void requireVisibleSensor(Long sensorChipId) {
        // Чип бывает в каталоге дважды: отдельным устройством и датчиком слота блока.
        // Виден он тому, кому виден ЛЮБОЙ из владельцев, поэтому перебираем все строки:
        // одна случайно выбранная строка скрывала настройку у того, кому виден блок.
        for (Sensor sensor : sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(sensorChipId)) {
            if (sensor.getDeviceId() != null && access.canView(sensor.getDeviceId())) {
                return;
            }
        }
        if (access.canView(sensorChipId)) {
            return;
        }
        throw new HttpStatusException(HttpStatus.NOT_FOUND, "Sensor not found");
    }

    /**
     * Перенос настроек при принятии приглашения: новичок получает настройки пригласившего
     * по вновь открытым устройствам и по их датчикам. Уже заданные новичком значения
     * не трогаем, а его собственные настройки администратора не копируются никогда —
     * это личные настройки каждого.
     */
    @Transactional
    public void copyFrom(Long fromUserId, Long toUserId, Long chipId) {
        devicePrefs.findByUserIdAndChipId(fromUserId, chipId)
                .filter(p -> p.getName() != null || !p.isHideVacc())
                .ifPresent(src -> devicePrefs.findByUserIdAndChipId(toUserId, chipId)
                        .ifPresentOrElse(
                                dst -> {
                                    if (dst.getName() == null) {
                                        dst.setName(src.getName());
                                    }
                                    if (src.isHideVacc() != dst.isHideVacc()) {
                                        dst.setHideVacc(src.isHideVacc());
                                    }
                                    devicePrefs.save(dst);
                                },
                                () -> devicePrefs.save(copyOf(fromUserId, toUserId, src))));
        for (UserSensorPref src : sensorPrefs.findByUserId(fromUserId)) {
            boolean belongs = chipId.equals(src.getSensorChipId())
                    || sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(src.getSensorChipId()).stream()
                            .anyMatch(s -> chipId.equals(s.getDeviceId()));
            if (!belongs || sensorPrefs.findByUserIdAndSensorChipId(toUserId, src.getSensorChipId()).isPresent()) {
                continue;
            }
            UserSensorPref dst = new UserSensorPref();
            dst.setUserId(toUserId);
            dst.setSensorChipId(src.getSensorChipId());
            dst.setHiddenFields(src.getHiddenFields());
            dst.setYRange(src.getYRange());
            sensorPrefs.save(dst);
        }
    }

    private UserDevicePref copyOf(Long fromUserId, Long toUserId, UserDevicePref src) {
        UserDevicePref dst = new UserDevicePref();
        dst.setUserId(toUserId);
        dst.setChipId(src.getChipId());
        dst.setHideVacc(src.isHideVacc());
        dst.setName(src.getName());
        return dst;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String joinHidden(List<String> fields) {
        List<String> clean = new ArrayList<>();
        for (String f : fields) {
            if (f != null && !f.isBlank() && !clean.contains(f.trim())) {
                clean.add(f.trim());
            }
        }
        return String.join(",", clean);
    }

    private static List<String> splitHidden(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String writeYRange(Map<String, YRange> range) {
        if (range == null || range.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, YRange> e : range.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            YRange r = e.getValue();
            sb.append('{');
            if (r != null && r.min() != null) {
                sb.append("\"min\":").append(r.min());
            }
            if (r != null && r.max() != null) {
                if (r.min() != null) {
                    sb.append(',');
                }
                sb.append("\"max\":").append(r.max());
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private static Map<String, YRange> parseYRange(String raw) {
        Map<String, YRange> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(v\\d+)\"\\s*:\\s*\\{\\s*\"?min\"?\\s*:\\s*(-?[\\d.]+)?\\s*,?\\s*\"?max\"?\\s*:\\s*(-?[\\d.]+)?")
                .matcher(raw);
        while (m.find()) {
            out.put(m.group(1), new YRange(num(m.group(2)), num(m.group(3))));
        }
        return out;
    }

    private static Float num(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Float.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Настройки устройств (блоков и автономных датчиков). */
    @Serdeable
    public record DevicePref(boolean hideVacc, String name) {
    }

    /** Настройки конкретного датчика. */
    @Serdeable
    public record SensorPref(List<String> hiddenFields, Map<String, YRange> yRange) {
    }

    @Serdeable
    public record YRange(Float min, Float max) {
    }

    /**
     * Полный ответ: всё, что сервер знает о настройках пользователя.
     *
     * ALWAYS важен: без него пустые секции выпадают из JSON, и клиент не может отличить
     * «настроек нет» от «сервер не прислал раздел».
     */
    @Serdeable
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Prefs(Map<String, DevicePref> devices, Map<String, SensorPref> sensors,
                        Map<String, Map<String, FieldPref>> fields,
                        Map<String, Map<String, FieldPref>> defaults) {
    }

    /** Частичное обновление: ключи верхнего уровня, которые не пришли, не трогаются. */
    @Serdeable
    public record PrefsPatch(Map<String, DevicePrefPatch> devices, Map<String, SensorPrefPatch> sensors,
                             Map<String, Map<String, FieldPref>> fields) {
    }

    /** Подпись и единица канала для типа датчика. */
    @Serdeable
    public record FieldPref(String label, String unit) {
    }

    @Serdeable
    public record DevicePrefPatch(Boolean hideVacc, String name) {
    }

    @Serdeable
    public record SensorPrefPatch(List<String> hiddenFields, Map<String, YRange> yRange) {
    }
}