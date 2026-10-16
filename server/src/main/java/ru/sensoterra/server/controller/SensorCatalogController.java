package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.CommandLogRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.DeviceStateStore;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller("/api/sensors")
@Singleton
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SensorCatalogController {

    private static final Logger log = LoggerFactory.getLogger(SensorCatalogController.class);

    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository sensorRegistrationRepository;
    private final DeviceAccessService access;
    private final MqttClientService mqttClientService;
    private final CommandLogRepository commandLogRepository;
    private final DeviceStateStore stateStore;

    public SensorCatalogController(DeviceRepository deviceRepository,
                                   SensorRepository sensorRepository,
                                   SensorRegistrationRepository sensorRegistrationRepository,
                                   DeviceAccessService access,
                                   MqttClientService mqttClientService,
                                   CommandLogRepository commandLogRepository,
                                   DeviceStateStore stateStore) {
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.sensorRegistrationRepository = sensorRegistrationRepository;
        this.access = access;
        this.mqttClientService = mqttClientService;
        this.commandLogRepository = commandLogRepository;
        this.stateStore = stateStore;
    }

    /**
     * Каталог всех датчиков с их ролями:
     *  - INDIVIDUAL  — датчик публикует сам (топик chipId/VALUES)
     *  - BLOCK       — датчик работает через блок реле (топик blockId/EVENT_Sn),
     *                  зарегистрирован в слот slot блока blockId.
     * Если один датчик зарегистрирован сразу на несколько блоков — это конфликт
     * (conflict=true), исправить можно только вручную (ESP в режиме AP).
     */
    @Get("/catalog")
    public List<SensorCatalogEntry> catalog() {
        java.util.Set<Long> visible = access.visibleChipIds();

        Map<Long, Accumulator> byChip = new LinkedHashMap<>();

        List<Device> individualDevices = deviceRepository.findByDeviceType("INDIVIDUAL_SENSOR").stream()
                .filter(d -> visible.contains(d.getChipId()))
                .toList();
        for (Device d : individualDevices) {
            byChip.computeIfAbsent(d.getChipId(), Accumulator::new).individual = true;
        }

        List<Device> blockDevices = deviceRepository.findByDeviceType("BLOCK").stream()
                .filter(d -> visible.contains(d.getChipId()))
                .toList();
        for (Device block : blockDevices) {
            List<SensorRegistration> regs =
                    sensorRegistrationRepository.findByDeviceIdAndSensorChipIdIsNotNull(block.getChipId());
            for (SensorRegistration reg : regs) {
                byChip.computeIfAbsent(reg.getSensorChipId(), Accumulator::new)
                        .blockRoles.add(new BlockRole(block.getChipId(), reg.getSlot()));
            }
        }

        List<SensorCatalogEntry> result = new ArrayList<>();
        Long serverNow = Instant.now().toEpochMilli();
        for (Accumulator acc : byChip.values()) {
            Sensor sensor = sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(acc.chipId)
                    .stream().findFirst().orElse(null);
            Integer send = sensor != null ? sensor.getSend() : null;
            // last_time — метка последних данных (для retained-сообщений без разбора
            // Time не обновляется). updated_at меняется при любом изменении строки,
            // в т.ч. при переигровке retained и правках настроек, поэтому как
            // признак «данные были» он не годится: мёртвый датчик выглядел бы живым.
            Long lastTs = sensor == null ? null
                    : sensor.getLastTime() != null ? sensor.getLastTime().toEpochMilli()
                    : sensor.getUpdatedAt() != null ? sensor.getUpdatedAt().toEpochMilli()
                    : null;
            boolean individual = acc.individual && acc.blockRoles.isEmpty();
            result.add(new SensorCatalogEntry(
                    acc.chipId,
                    resolveName(acc.chipId),
                    individual,
                    send,
                    lastTs,
                    acc.blockRoles,
                    acc.blockRoles.size() > 1,
                    sensor != null ? sensor.getSleep() : null,
                    sensor != null ? sensor.getPercentN() : null,
                    sensor != null ? sensor.getPercentS() : null,
                    sensor != null ? sensor.getMinVoltage() : null,
                    sensor != null ? sensor.getSensorType() : null,
                    serverNow));
        }
        return result;
    }

    /**
     * Удалённое разрешение конфликта датчика: keepBlockId — блок, где регистрацию нужно
     * оставить; всем остальным блокам, где датчик зарегистрирован, публикуется UNREG.
     * Строки sensor_registration удаляет сам сервер по приходящему SENSORS блока.
     */
    @Post("/{chipId}/resolve-conflict")
    public ResolveResult resolveConflict(Long chipId, @Body ResolveConflictRequest request) {
        List<SensorRegistration> registrations = sensorRegistrationRepository.findBySensorChipId(chipId);
        List<Long> blockIds = registrations.stream()
                .map(SensorRegistration::getDeviceId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (blockIds.size() < 2) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Нет конфликта: датчик зарегистрирован на " + blockIds.size() + " блок(е/ах)");
        }
        Long keepBlockId = request == null ? null : request.keepBlockId();
        if (keepBlockId == null || !blockIds.contains(keepBlockId)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "keepBlockId должен быть одним из блоков, где зарегистрирован датчик (" + blockIds + ")");
        }
        if (!mqttClientService.isConnected()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT broker not connected");
        }

        List<Long> unregistered = new ArrayList<>();
        List<Long> offline = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long blockId : blockIds) {
            if (blockId.equals(keepBlockId)) {
                continue;
            }
            checkBlockAccess(blockId);
            boolean sent = mqttClientService.publishUnregister(blockId, chipId);
            if (sent) {
                unregistered.add(blockId);
                if (!"online".equals(stateStore.getStatus(blockId))) {
                    offline.add(blockId);
                }
                commandLogRepository.save(log(blockId, chipId));
            } else {
                failed.add(blockId);
            }
        }
        log.info("Resolved sensor {} conflict: keep {}, unregistered {}, offline {}, failed {}",
                chipId, keepBlockId, unregistered, offline, failed);
        return new ResolveResult(chipId, keepBlockId, unregistered, offline, failed);
    }

    private void checkBlockAccess(Long blockId) {
        access.requireControl(blockId);
    }

    private ru.sensoterra.server.entity.CommandLog log(Long blockId, Long sensorChipId) {
        ru.sensoterra.server.entity.CommandLog commandLog = new ru.sensoterra.server.entity.CommandLog();
        commandLog.setDeviceId(blockId);
        sensorRepository.findBySensorChipId(sensorChipId).ifPresent(s -> commandLog.setSensorId(s.getId()));
        commandLog.setCommandType("SENSOR_UNREG");
        commandLog.setPayload(sensorChipId + ";" + mqttClientService.firmwareTime(blockId));
        commandLog.setTopic(blockId + "/UNREG");
        commandLog.setSentAt(Instant.now());
        commandLog.setConfirmed(false);
        return commandLog;
    }

    private String resolveName(Long chipId) {
        for (Device d : deviceRepository.findByDeviceType("INDIVIDUAL_SENSOR")) {
            if (d.getChipId().equals(chipId) && d.getName() != null) {
                return d.getName();
            }
        }
        return sensorRepository.findBySensorChipIdAndLastTimeIsNotNullOrderByLastTimeDesc(chipId)
                .stream().findFirst()
                .map(Sensor::getName)
                .orElse(null);
    }

    /**
     * Список реально существующих типов датчиков (distinct sensor_type из таблицы sensor).
     * Источник для страницы «Настройки»: показываем секцию полей для КАЖДОГО существующего типа,
     * а не только для type=0. По умолчанию в системе один тип (0); при появлении датчиков
     * с type != 0 (прошивки с полем Type в EVENT_S/VALUES) список расширится сам.
     */
    @Get("/types")
    public List<Short> types() {
        return sensorRepository.findDistinctSensorTypes();
    }

    @Serdeable
    public record SensorCatalogEntry(Long chipId, String name, Boolean individual,
                                     Integer send, Long lastTs,
                                     List<BlockRole> blockRoles, Boolean conflict,
                                     Integer sleep, Short percentN, Short percentS,
                                     Integer minVoltage, Short sensorType, Long serverNow) {
    }

    @Serdeable
    public record BlockRole(Long blockId, Short slot) {
    }

    @Serdeable
    public record ResolveConflictRequest(Long keepBlockId) {
    }

    @Serdeable
    public record ResolveResult(Long chipId, Long keepBlockId,
                                List<Long> unregisteredBlocks,
                                List<Long> offlineBlocks,
                                List<Long> failedBlocks) {
    }

    private static class Accumulator {
        Long chipId;
        boolean individual = false;
        List<BlockRole> blockRoles = new ArrayList<>();

        Accumulator(Long chipId) {
            this.chipId = chipId;
        }
    }
}