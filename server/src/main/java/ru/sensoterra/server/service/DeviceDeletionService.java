package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Полное удаление устройства: сброс retained-сообщений на брокере и очистка БД.
 *
 * Порядок важен: сначала снимается retain, потом чистится БД. Иначе удалённое
 * устройство немедленно восстановится — TelemetryService.upsertDevice создаёт
 * строку device при первом же доставленном retained-сообщении.
 *
 * Удаление блока каскадное, но только по его собственным данным. Датчик, который
 * зарегистрирован ещё и на другом блоке, переживает удаление целиком: у него
 * остаются телеметрия, retain, строка device и регистрация на том блоке. У
 * удаляемого блока от него убирается только слот в sensor_registration. Так
 * удаление одного блока не обнуляет соседний.
 */
@Singleton
public class DeviceDeletionService {

    private static final Logger log = LoggerFactory.getLogger(DeviceDeletionService.class);

    private static final Set<String> DELETABLE_TYPES = Set.of("INDIVIDUAL_SENSOR", "BLOCK");

    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository sensorRegistrationRepository;
    private final RelayRepository relayRepository;
    private final EntityManager entityManager;
    private final MqttClientService mqttClientService;
    private final DeviceStateStore deviceStateStore;
    private final DeviceZoneService deviceZoneService;

    public DeviceDeletionService(DeviceRepository deviceRepository,
                                 SensorRepository sensorRepository,
                                 SensorRegistrationRepository sensorRegistrationRepository,
                                 RelayRepository relayRepository,
                                 EntityManager entityManager,
                                 MqttClientService mqttClientService,
                                 DeviceStateStore deviceStateStore,
                                 DeviceZoneService deviceZoneService) {
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.sensorRegistrationRepository = sensorRegistrationRepository;
        this.relayRepository = relayRepository;
        this.entityManager = entityManager;
        this.mqttClientService = mqttClientService;
        this.deviceStateStore = deviceStateStore;
        this.deviceZoneService = deviceZoneService;
    }

    @Serdeable
    public record DeletionResult(Long chipId,
                                 String deviceType,
                                 List<Long> sensorChipIds,
                                 List<Long> sharedSensorChipIds,
                                 boolean mqttConnected,
                                 int retainedTopicsCleared,
                                 int retainedTopicsTotal,
                                 Map<String, Integer> deletedRows) {
    }

    @Transactional
    public DeletionResult deleteDevice(Long chipId) {
        Device device = deviceRepository.findByChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (!DELETABLE_TYPES.contains(device.getDeviceType())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Only standalone sensors and blocks can be deleted, device type is " + device.getDeviceType());
        }

        List<Long> sensorChipIds = collectSensorChipIds(device);
        List<Long> exclusiveSensorChipIds = new ArrayList<>();
        List<Long> sharedSensorChipIds = new ArrayList<>();
        for (Long sensorChipId : sensorChipIds) {
            if (isRegisteredOnAnotherBlock(sensorChipId, chipId)) {
                sharedSensorChipIds.add(sensorChipId);
            } else {
                exclusiveSensorChipIds.add(sensorChipId);
            }
        }

        List<Long> purgeChipIds = new ArrayList<>();
        purgeChipIds.add(chipId);
        purgeChipIds.addAll(exclusiveSensorChipIds);

        int total = 0;
        int cleared = 0;
        for (Long id : purgeChipIds) {
            MqttClientService.RetainedClearResult result = mqttClientService.clearRetainedForDevice(id);
            total += result.total();
            cleared += result.cleared();
        }

        Map<String, Integer> deleted = new LinkedHashMap<>();
        purgeDatabase(chipId, exclusiveSensorChipIds, deleted);
        deleteDeviceRows(chipId, exclusiveSensorChipIds, deleted);

        for (Long id : purgeChipIds) {
            deviceStateStore.remove(id);
            deviceZoneService.remove(id);
        }
        log.info("Deleted device {} of type {} (sensors {} kept {}): retained cleared {}/{}, rows {}",
                chipId, device.getDeviceType(), sensorChipIds, sharedSensorChipIds, cleared, total, deleted);
        return new DeletionResult(chipId, device.getDeviceType(), sensorChipIds, sharedSensorChipIds,
                mqttClientService.isConnected(), cleared, total, deleted);
    }

    /**
     * Собирает chipId всех датчиков блока до того, как их регистрации будут удалены.
     * Источники — слоты SENSORS, строки телеметрии и привязки реле.
     */
    private List<Long> collectSensorChipIds(Device device) {
        if (!"BLOCK".equals(device.getDeviceType())) {
            return List.of();
        }
        Set<Long> chipIds = new LinkedHashSet<>();
        for (SensorRegistration registration : sensorRegistrationRepository.findByDeviceId(device.getChipId())) {
            addIfPresent(chipIds, registration.getSensorChipId());
        }
        for (Sensor sensor : sensorRepository.findByDeviceId(device.getChipId())) {
            addIfPresent(chipIds, sensor.getSensorChipId());
        }
        for (Relay relay : relayRepository.findByDeviceIdOrderByRelayNumberAsc(device.getChipId())) {
            addIfPresent(chipIds, relay.getSensorId());
        }
        return List.copyOf(chipIds);
    }

    private boolean isRegisteredOnAnotherBlock(Long sensorChipId, Long blockChipId) {
        return sensorRegistrationRepository.findBySensorChipId(sensorChipId).stream()
                .anyMatch(registration -> !blockChipId.equals(registration.getDeviceId()));
    }

    private void addIfPresent(Set<Long> chipIds, Long chipId) {
        if (chipId != null && chipId != 0) {
            chipIds.add(chipId);
        }
    }

    /**
     * Чистит все таблицы, где фигурируют chipId удаляемого устройства и его
     * эксклюзивных датчиков.
     *
     * Регистрации и строки sensor фильтруются по device_id блока, а не по
     * sensor_chip_id: датчик может стоять в слотах нескольких блоков, и его
     * регистрация на чужом блоке должна выжить.
     *
     * Гипертаблицы Timescale и таблицы сценариев не имеют FK на device, поэтому
     * каскадом их не убрать. Таблицы проверяются на существование: в тестах схема
     * создаётся Hibernate по JPA-сущностям, и части Flyway-таблиц там нет.
     * Явные DELETE по sensor/relay/command_log дублируют каскад — в тестовой схеме
     * внешних ключей тоже нет, и без них поведение отличалось бы от продового.
     */
    private void purgeDatabase(Long blockChipId, List<Long> exclusiveSensors, Map<String, Integer> deleted) {
        List<Long> chips = new ArrayList<>();
        chips.add(blockChipId);
        chips.addAll(exclusiveSensors);
        Map<String, Object> both = Map.of("chips", chips, "block", blockChipId);
        Map<String, Object> onlyChips = Map.of("chips", chips);
        Map<String, Object> onlyBlock = Map.of("block", blockChipId);

        delete("command_log", """
                DELETE FROM command_log
                WHERE device_id IN (:chips)
                   OR sensor_id IN (SELECT id FROM sensor WHERE device_id = :block)
                """, both, deleted);
        delete("relay", """
                DELETE FROM relay
                WHERE device_id IN (:chips)
                   OR sensor_id IN (SELECT id FROM sensor WHERE device_id = :block)
                """, both, deleted);
        delete("sensor_telemetry", """
                DELETE FROM sensor_telemetry
                WHERE device_id IN (:chips) OR sensor_chip_id IN (:chips)
                """, onlyChips, deleted);
        delete("relay_event", "DELETE FROM relay_event WHERE device_id IN (:chips)", onlyChips, deleted);
        delete("block_telemetry", "DELETE FROM block_telemetry WHERE device_id IN (:chips)", onlyChips, deleted);
        delete("device_status_log", "DELETE FROM device_status_log WHERE device_id IN (:chips)", onlyChips, deleted);
        delete("automation_event", """
                DELETE FROM automation_event
                WHERE block_id IN (:chips) OR sensor_chip_id IN (:chips)
                """, onlyChips, deleted);
        delete("automation_condition", """
                DELETE FROM automation_condition WHERE sensor_chip_id IN (:chips)
                """, onlyChips, deleted);
        delete("automation_action", "DELETE FROM automation_action WHERE block_id IN (:chips)", onlyChips, deleted);
        delete("sensor_registration", "DELETE FROM sensor_registration WHERE device_id = :block", onlyBlock, deleted);
        delete("relay", "DELETE FROM relay WHERE device_id IN (:chips)", onlyChips, deleted);
        delete("command_log", "DELETE FROM command_log WHERE device_id IN (:chips)", onlyChips, deleted);
        delete("sensor", "DELETE FROM sensor WHERE device_id IN (:chips)", onlyChips, deleted);
    }

    /**
     * Удаляет строку device самого блока и строки device его эксклюзивных
     * датчиков. Датчик удаляется как отдельное устройство только если он и правда
     * блочный: если автономный датчик был случайно зарегистрирован в слоте, его
     * собственную карточку устройства трогать нельзя.
     */
    private void deleteDeviceRows(Long chipId, List<Long> exclusiveSensors, Map<String, Integer> deleted) {
        List<Long> toDelete = new ArrayList<>();
        toDelete.add(chipId);
        for (Long sensorChipId : exclusiveSensors) {
            if (sensorChipId.equals(chipId)) {
                continue;
            }
            String type = deviceRepository.findByChipId(sensorChipId)
                    .map(Device::getDeviceType)
                    .orElse(null);
            if ("BLOCK_SENSOR".equals(type)) {
                toDelete.add(sensorChipId);
            }
        }
        for (Long id : toDelete) {
            deviceRepository.deleteById(id);
        }
        deleted.merge("device", toDelete.size(), Integer::sum);
    }

    private void delete(String table, String sql, Map<String, Object> params, Map<String, Integer> deleted) {
        if (!tableExists(table)) {
            log.debug("Table {} is absent, skipping", table);
            return;
        }
        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        int rows = query.executeUpdate();
        if (rows > 0) {
            deleted.merge(table, rows, Integer::sum);
        }
    }

    private boolean tableExists(String table) {
        Object result = entityManager.createNativeQuery(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = :tableName")
                .setParameter("tableName", table)
                .getSingleResult();
        return ((Number) result).intValue() > 0;
    }
}
