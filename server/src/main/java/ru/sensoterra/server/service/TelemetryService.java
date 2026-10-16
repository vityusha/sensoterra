package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.*;
import ru.sensoterra.server.repository.*;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Singleton
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DeviceRepository deviceRepository;
    private final RelayRepository relayRepository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository sensorRegistrationRepository;
    private final EntityManager entityManager;
    private final SensorCalibrationService calibrationService;

    public TelemetryService(DeviceRepository deviceRepository,
                            RelayRepository relayRepository,
                            SensorRepository sensorRepository,
                            SensorRegistrationRepository sensorRegistrationRepository,
                            EntityManager entityManager,
                            SensorCalibrationService calibrationService) {
        this.deviceRepository = deviceRepository;
        this.relayRepository = relayRepository;
        this.sensorRepository = sensorRepository;
        this.sensorRegistrationRepository = sensorRegistrationRepository;
        this.entityManager = entityManager;
        this.calibrationService = calibrationService;
    }

    @Transactional
    public Device upsertDevice(Long chipId, String deviceType) {
        Device device = deviceRepository.findByChipId(chipId).orElse(null);
        if (device == null) {
            if ("INDIVIDUAL_SENSOR".equals(deviceType) && isRegisteredOnBlock(chipId)) {
                deviceType = "BLOCK_SENSOR";
            }
            try {
                entityManager.createNativeQuery(
                        "INSERT INTO device (chip_id, device_type, created_at) " +
                        "VALUES (:chipId, :deviceType, now()) " +
                        "ON CONFLICT (chip_id) DO NOTHING")
                        .setParameter("chipId", chipId)
                        .setParameter("deviceType", deviceType)
                        .executeUpdate();
            } catch (Exception e) {
                log.warn("Device {} register failed: {}", chipId, e.getMessage());
            }
            device = deviceRepository.findByChipId(chipId).orElse(null);
            if (device != null) {
                log.info("Auto-registered device {} ({})", chipId, deviceType);
            } else {
                device = new Device();
                device.setChipId(chipId);
                device.setDeviceType(deviceType);
                device.setCreatedAt(Instant.now());
            }
        } else if (device.getDeviceType() == null) {
            if ("INDIVIDUAL_SENSOR".equals(deviceType) && isRegisteredOnBlock(chipId)) {
                deviceType = "BLOCK_SENSOR";
            }
            device.setDeviceType(deviceType);
            deviceRepository.save(device);
        } else if ("INDIVIDUAL_SENSOR".equals(device.getDeviceType()) && isRegisteredOnBlock(chipId)) {
            device.setDeviceType("BLOCK_SENSOR");
            deviceRepository.save(device);
            log.info("Auto-reclassified device {} to BLOCK_SENSOR", chipId);
        }
        return device;
    }

    public boolean isRegisteredOnBlock(Long sensorChipId) {
        if (sensorChipId == null) {
            return false;
        }
        return !sensorRegistrationRepository
                .findBySensorChipId(sensorChipId)
                .isEmpty();
    }

    public boolean isSensorRegisteredInSlot(Long deviceId, int slot, Long sensorChipId) {
        if (deviceId == null || sensorChipId == null) {
            return false;
        }
        return sensorRegistrationRepository
                .findByDeviceIdAndSlot(deviceId, (short) slot)
                .map(reg -> sensorChipId.equals(reg.getSensorChipId()))
                .orElse(false);
    }

    @Transactional
    public boolean isStaleDuplicate(Long deviceId, Long sensorChipId, Integer numMessage, Instant time) {
        return sensorRepository
                .findByDeviceIdAndSensorChipId(deviceId, sensorChipId)
                .map(s -> time != null
                        ? java.util.Objects.equals(s.getNumMessage(), numMessage)
                        && java.util.Objects.equals(s.getLastTime(), time)
                        : java.util.Objects.equals(s.getNumMessage(), numMessage))
                .orElse(false);
    }

    @Transactional
    public void updateRegisteredSensors(Long deviceId, java.util.Map<Integer, Long> slots) {
        for (java.util.Map.Entry<Integer, Long> entry : slots.entrySet()) {
            Short slot = entry.getKey().shortValue();
            Long chipId = entry.getValue();
            if (chipId == null || chipId == 0) {
                sensorRegistrationRepository
                        .findByDeviceIdAndSlot(deviceId, slot)
                        .ifPresent(sensorRegistrationRepository::delete);
                continue;
            }
            SensorRegistration reg = sensorRegistrationRepository
                    .findByDeviceIdAndSlot(deviceId, slot)
                    .orElseGet(SensorRegistration::new);
            reg.setDeviceId(deviceId);
            reg.setSlot(slot);
            reg.setSensorChipId(chipId);
            reg.setUpdatedAt(Instant.now());
            try {
                sensorRegistrationRepository.save(reg);
            } catch (DataAccessException e) {
                log.warn("SensorRegistration upsert failed for device {}, slot {}: {}",
                        deviceId, slot, e.getMessage());
            }
            if (sensorRepository.findByDeviceIdAndSensorChipId(deviceId, chipId).isEmpty()) {
                Sensor sensor = new Sensor();
                sensor.setDeviceId(deviceId);
                sensor.setSensorChipId(chipId);
                sensor.setSensorNumber(slot);
                sensor.setUpdatedAt(Instant.now());
                sensorRepository.save(sensor);
            }
        }
        java.util.Set<Long> registered = slots.values().stream()
                .filter(c -> c != null && c != 0)
                .collect(java.util.stream.Collectors.toSet());
        java.util.List<Sensor> orphans = sensorRepository.findByDeviceId(deviceId).stream()
                .filter(s -> s.getSensorChipId() != null && !registered.contains(s.getSensorChipId()))
                .toList();
        if (!orphans.isEmpty()) {
            sensorRepository.deleteAll(orphans);
            log.info("Deleted {} orphan sensor row(s) for block {} (unregistered): {}",
                    orphans.size(), deviceId,
                    orphans.stream().map(Sensor::getSensorChipId).toList());
        }
    }

    @Transactional
    public void onRelayEvent(Long deviceId, int relayNumber, Instant time, Integer state,
                             boolean fault, Integer rssiR, Float vbat, Float vacc) {
        RelayEvent event = new RelayEvent();
        event.setTime(time != null ? time : Instant.now());
        event.setDeviceId(deviceId);
        event.setRelayNumber((short) relayNumber);
        event.setState(state != null ? state.shortValue() : null);
        event.setFault(fault);
        event.setRssiR(rssiR);
        event.setVbat(vbat);
        event.setVacc(vacc);
        try {
            entityManager.persist(event);
        } catch (Exception e) {
            log.warn("RelayEvent insert failed: {}", e.getMessage());
        }
        // EVENT_R — событие канала (включение по таймеру, по датчику, ручное): оно меняет
        // фактическое состояние канала. STATE_R приходит только при реконнекте/настройке,
        // поэтому без обновления тут страница показывала бы «вкл», пока блок не переподключится.
        if (state != null) {
            relayRepository.findByDeviceIdAndRelayNumber(deviceId, (short) relayNumber)
                    .ifPresent(relay -> {
                        relay.setState(state.shortValue());
                        relay.setRssiR(rssiR);
                        relay.setVbat(vbat);
                        relay.setVacc(vacc);
                        relay.setUpdatedAt(Instant.now());
                        try {
                            relayRepository.update(relay);
                        } catch (DataAccessException e) {
                            log.warn("Relay state update failed for device {}, relay {}: {}",
                                    deviceId, relayNumber, e.getMessage());
                        }
                    });
        }
    }

    @Transactional
    public void upsertRelay(Long deviceId, RelayData relayData) {
        upsertDevice(deviceId, "BLOCK");
        Relay relay = relayRepository
                .findByDeviceIdAndRelayNumber(deviceId, relayData.relayNumber)
                .orElseGet(Relay::new);
        relay.setDeviceId(deviceId);
        relay.setRelayNumber(relayData.relayNumber.shortValue());
        relay.setName(relayData.name);
        relay.setState(relayData.state);
        relay.setActivity(relayData.activity);
        relay.setTypeEnum(relayData.type);
        relay.setTimerEnabled(relayData.timerEnabled);
        relay.setDataType(relayData.dataType);
        relay.setMainTime(relayData.mainTime);
        relay.setDurationMain(relayData.durationMain);
        relay.setExtraTime(relayData.extraTime);
        relay.setDurationExtra(relayData.durationExtra);
        relay.setIntervalH(relayData.intervalH);
        relay.setSensorId(relayData.sensorId);
        relay.setStartTime(relayData.startTime);
        relay.setFinishTime(relayData.finishTime);
        relay.setOneLimit(relayData.oneLimit);
        relay.setLowLimit(relayData.lowLimit);
        relay.setHighLimit(relayData.highLimit);
        relay.setRssiR(relayData.rssiR);
        relay.setVbat(relayData.vbat);
        relay.setVacc(relayData.vacc);
        relay.setUpdatedAt(Instant.now());
        try {
            relayRepository.save(relay);
        } catch (DataAccessException e) {
            log.warn("Relay upsert failed for device {}, relay {}: {}",
                    deviceId, relayData.relayNumber, e.getMessage());
        }
    }

    @Transactional
    public void onSensorEvent(Long deviceId, Long sensorChipId, SensorData sensorData, Instant time) {
        upsertSensor(deviceId, sensorChipId, sensorData, time);

        SensorTelemetry telemetry = new SensorTelemetry();
        telemetry.setTime(time != null ? time : Instant.now());
        telemetry.setDeviceId(deviceId);
        telemetry.setSensorChipId(sensorChipId);
        telemetry.setSensorName(sensorData.name);
        telemetry.setSensorType(sensorData.sensorType);
        telemetry.setMoistureAdc(sensorData.moistureAdc);
        telemetry.setTemperature(sensorData.temperature);
        telemetry.setHumidity(sensorData.humidity);
        telemetry.setLight(sensorData.light);
        telemetry.setBattery(sensorData.battery);
        telemetry.setBatload(sensorData.batload);
        telemetry.setPower(sensorData.power);
        telemetry.setRssi(sensorData.rssi);
        telemetry.setVbat(sensorData.vbat);
        telemetry.setVacc(sensorData.vacc);
        telemetry.setRssiBlock(sensorData.rssiBlock);
        telemetry.setNumMessage(sensorData.numMessage);
        telemetry.setError(sensorData.error);
        telemetry.setFieldsJson(toJson(sensorData.fields));
        try {
            entityManager.persist(telemetry);
        } catch (Exception e) {
            log.warn("SensorTelemetry insert failed: {}", e.getMessage());
        }
    }

    @Transactional
    public void saveBlockTelemetry(Long deviceId, Integer rssiR, Float vbat, Float vacc) {
        if (rssiR == null && vbat == null && vacc == null) {
            return;
        }
        try {
            entityManager.createNativeQuery(
                    "INSERT INTO block_telemetry (time, device_id, rssi_r, vbat, vacc) " +
                    "VALUES (now(), :deviceId, :rssiR, :vbat, :vacc)")
                    .setParameter("deviceId", deviceId)
                    .setParameter("rssiR", rssiR)
                    .setParameter("vbat", vbat)
                    .setParameter("vacc", vacc)
                    .executeUpdate();
        } catch (Exception e) {
            log.warn("Block telemetry insert failed for device {}: {}", deviceId, e.getMessage());
        }
    }

    @Transactional
    public void saveDeviceStatus(Long deviceId, String status) {
        try {
            entityManager.createNativeQuery(
                    "INSERT INTO device_status_log (time, device_id, status) " +
                    "VALUES (now(), :deviceId, :status)")
                    .setParameter("deviceId", deviceId)
                    .setParameter("status", status)
                    .executeUpdate();
        } catch (Exception e) {
            log.warn("Device status insert failed for device {}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Последний известный STATUS каждого устройства из device_status_log.
     * Используется для восстановления статусов в DeviceStateStore при старте сервера,
     * чтобы блоки с persistent MQTT-сессией (не переподключаются и не публикуют
     * STATUS online повторно) не "залипали" оффлайн до реального события.
     */
    public java.util.Map<Long, String> loadLastDeviceStatuses() {
        java.util.Map<Long, String> result = new java.util.LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT ON (device_id) device_id, status " +
                    "FROM device_status_log " +
                    "ORDER BY device_id, time DESC")
                    .getResultList();
            for (Object[] r : rows) {
                if (r[0] == null) {
                    continue;
                }
                Long deviceId = ((Number) r[0]).longValue();
                String status = r[1] != null ? r[1].toString() : "unknown";
                result.put(deviceId, status);
            }
        } catch (Exception e) {
            log.warn("Failed to load last device statuses: {}", e.getMessage());
        }
        return result;
    }

    private String toJson(java.util.Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            log.warn("Sensor fields serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private void upsertSensor(Long deviceId, Long sensorChipId, SensorData sensorData, Instant time) {
        Sensor sensor = sensorRepository
                .findByDeviceIdAndSensorChipId(deviceId, sensorChipId)
                .orElseGet(Sensor::new);
        sensor.setDeviceId(deviceId);
        sensor.setSensorChipId(sensorChipId);
        if (sensorData.sensorNumber != null) sensor.setSensorNumber(sensorData.sensorNumber);
        if (sensorData.name != null) sensor.setName(sensorData.name);
        // Тип по умолчанию — 0 (влажность почвы); из values приходит только если не 0.
        if (sensorData.sensorType != null) {
            sensor.setSensorType(sensorData.sensorType);
        } else if (sensor.getSensorType() == null) {
            sensor.setSensorType((short) 0);
        }
        if (sensorData.sleep != null) sensor.setSleep(sensorData.sleep);
        if (sensorData.send != null) sensor.setSend(sensorData.send);
        if (sensorData.percentN != null) sensor.setPercentN(sensorData.percentN);
        if (sensorData.percentS != null) sensor.setPercentS(sensorData.percentS);
        if (sensorData.minVoltage != null) sensor.setMinVoltage(sensorData.minVoltage);
        sensor.setMoistureAdc(sensorData.moistureAdc);
        sensor.setTemperature(sensorData.temperature);
        sensor.setHumidity(sensorData.humidity);
        sensor.setLight(sensorData.light);
        sensor.setBattery(sensorData.battery);
        sensor.setBatload(sensorData.batload);
        sensor.setPower(sensorData.power);
        if (sensorData.batMin != null) sensor.setBatMin(sensorData.batMin);
        sensor.setRssi(sensorData.rssi);
        sensor.setRssiBlock(sensorData.rssiBlock);
        sensor.setVbatBlock(sensorData.vbat);
        sensor.setVaccBlock(sensorData.vacc);
        if (sensorData.numMessage != null) sensor.setNumMessage(sensorData.numMessage);
        if (sensorData.firmwareVersion != null) sensor.setFirmwareVersion(sensorData.firmwareVersion);
        if (sensorData.error != null) sensor.setError(sensorData.error);
        if (sensorData.fields != null) sensor.setFieldsJson(toJson(sensorData.fields));
        if (time != null) sensor.setLastTime(time);
        sensor.setUpdatedAt(Instant.now());
        sensorRepository.save(sensor);
        // При появлении датчика типа 0 заводятся дефолтные опорные точки калибровки
        // влажности (они же копируются существующим датчикам миграцией V26).
        calibrationService.ensureDefaults(sensor.getId(), sensor.getSensorType());
    }

    public static class RelayData {
        public final Short relayNumber;
        public final String name;
        public final Short state;
        public final Boolean activity;
        public final String type;
        public final Boolean timerEnabled;
        public final Short dataType;
        public final java.time.LocalTime mainTime;
        public final Short durationMain;
        public final java.time.LocalTime extraTime;
        public final Short durationExtra;
        public final Short intervalH;
        public final Long sensorId;
        public final java.time.LocalTime startTime;
        public final java.time.LocalTime finishTime;
        public final Integer oneLimit;
        public final Integer lowLimit;
        public final Integer highLimit;
        public final Integer rssiR;
        public final Float vbat;
        public final Float vacc;

        public RelayData(Short relayNumber) {
            this.relayNumber = relayNumber;
            this.name = null;
            this.state = null;
            this.activity = null;
            this.type = null;
            this.timerEnabled = null;
            this.dataType = null;
            this.mainTime = null;
            this.durationMain = null;
            this.extraTime = null;
            this.durationExtra = null;
            this.intervalH = null;
            this.sensorId = null;
            this.startTime = null;
            this.finishTime = null;
            this.oneLimit = null;
            this.lowLimit = null;
            this.highLimit = null;
            this.rssiR = null;
            this.vbat = null;
            this.vacc = null;
        }

        public RelayData with(String name, Short state, Boolean activity, String type, Boolean timerEnabled,
                              Short dataType, java.time.LocalTime mainTime, Short durationMain,
                              java.time.LocalTime extraTime, Short durationExtra, Short intervalH,
                              Long sensorId, java.time.LocalTime startTime, java.time.LocalTime finishTime,
                              Integer oneLimit, Integer lowLimit, Integer highLimit, Integer rssiR,
                              Float vbat, Float vacc) {
            return new RelayData(relayNumber, name, state, activity, type, timerEnabled, dataType,
                    mainTime, durationMain, extraTime, durationExtra, intervalH, sensorId,
                    startTime, finishTime, oneLimit, lowLimit, highLimit, rssiR, vbat, vacc);
        }

        private RelayData(Short relayNumber, String name, Short state, Boolean activity, String type,
                         Boolean timerEnabled, Short dataType, java.time.LocalTime mainTime,
                         Short durationMain, java.time.LocalTime extraTime, Short durationExtra,
                         Short intervalH, Long sensorId, java.time.LocalTime startTime,
                         java.time.LocalTime finishTime, Integer oneLimit, Integer lowLimit,
                         Integer highLimit, Integer rssiR, Float vbat, Float vacc) {
            this.relayNumber = relayNumber;
            this.name = name;
            this.state = state;
            this.activity = activity;
            this.type = type;
            this.timerEnabled = timerEnabled;
            this.dataType = dataType;
            this.mainTime = mainTime;
            this.durationMain = durationMain;
            this.extraTime = extraTime;
            this.durationExtra = durationExtra;
            this.intervalH = intervalH;
            this.sensorId = sensorId;
            this.startTime = startTime;
            this.finishTime = finishTime;
            this.oneLimit = oneLimit;
            this.lowLimit = lowLimit;
            this.highLimit = highLimit;
            this.rssiR = rssiR;
            this.vbat = vbat;
            this.vacc = vacc;
        }
    }

    public static class SensorData {
        public Short sensorNumber;
        public String name;
        public Short sensorType;
        public Integer numMessage;
        public Float battery;
        public Float batload;
        public Float power;
        public Float batMin;
        public Integer moistureAdc;
        public Float temperature;
        public Float humidity;
        public Integer light;
        public Integer sleep;
        public Integer send;
        public Short percentN;
        public Short percentS;
        public Integer minVoltage;
        public Integer rssi;
        public Integer rssiBlock;
        public Float vbat;
        public Float vacc;
        public Integer firmwareVersion;
        public String error;
        public java.util.Map<String, Object> fields;
    }
}