package ru.sensoterra.server.service;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sensoterra.server.entity.Alarm;
import ru.sensoterra.server.entity.BatteryType;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorCalibration;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.repository.AlarmRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.ws.DeviceStatusWebSocketBroadcaster;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Alarm states of devices, stored as episodes in the alarm table. An alarm that appears opens an
 * episode (or refreshes the value of the open one); its end closes the open episode of that type.
 *
 * <ul>
 *   <li>GAP — the sensor has not transmitted for 2 send intervals + 10 minutes;
 *       ends when it transmits again.</li>
 *   <li>LEVEL — moisture ADC above the middle between air and dry soil (the sensor is not in the
 *       soil); value is the ADC reading; checked on every message of the sensor.</li>
 *   <li>OFFLINE — a block went offline; ends when it is online again.</li>
 *   <li>BATTERY — sensor battery at or below its warning threshold (derived from the battery
 *       type); ends after recovering by more than 0.2 V or at 3.0 V.</li>
 *   <li>THRESHOLD_V0..V3 — reserved, not raised yet.</li>
 * </ul>
 *
 * Every opened or closed episode is pushed over the WebSocket as {@code type: "alarm"}.
 */
@Singleton
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    public static final String GAP = "GAP";
    public static final String LEVEL = "LEVEL";
    public static final String BATTERY = "BATTERY";
    public static final String OFFLINE = "OFFLINE";
    public static final String THRESHOLD_V0 = "THRESHOLD_V0";
    public static final String THRESHOLD_V1 = "THRESHOLD_V1";
    public static final String THRESHOLD_V2 = "THRESHOLD_V2";
    public static final String THRESHOLD_V3 = "THRESHOLD_V3";
    public static final List<String> TYPES = List.of(
            GAP, LEVEL, BATTERY, OFFLINE, THRESHOLD_V0, THRESHOLD_V1, THRESHOLD_V2, THRESHOLD_V3);

    /** A gap is GAP_MISSED_SENDS send intervals without data plus this grace time. */
    static final int GAP_MISSED_SENDS = 2;
    static final long GAP_GRACE_SECONDS = 10 * 60;
    /**
     * Gaps are judged only after MQTT has been up this long: right after a (re)connect the
     * broker replays retained messages and live data catches up. Before that sensor.last_time
     * still holds what the server saw before it went away, and every sensor would look silent.
     */
    static final long GAP_SETTLE_SECONDS = 2 * 60;

    private final AlarmRepository repository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository registrationRepository;
    private final DeviceRepository deviceRepository;
    private final SensorCalibrationService calibrationService;
    private final DeviceStatusWebSocketBroadcaster broadcaster;
    /** Since when the MQTT link is up; null while it is down (no data means nothing then). */
    private volatile Instant mqttUpSince;

    public AlarmService(AlarmRepository repository,
                        SensorRepository sensorRepository,
                        SensorRegistrationRepository registrationRepository,
                        DeviceRepository deviceRepository,
                        SensorCalibrationService calibrationService,
                        DeviceStatusWebSocketBroadcaster broadcaster) {
        this.repository = repository;
        this.sensorRepository = sensorRepository;
        this.registrationRepository = registrationRepository;
        this.deviceRepository = deviceRepository;
        this.calibrationService = calibrationService;
        this.broadcaster = broadcaster;
    }

    /**
     * Opens an episode, or updates the value of the open one. Returns true if a new episode
     * was opened.
     */
    public boolean raise(Long chipId, Long deviceId, String type, Instant startedAt, Double value) {
        Optional<Alarm> open = repository.findFirstByChipIdAndAlarmTypeAndEndedAtIsNull(chipId, type);
        if (open.isPresent()) {
            Alarm alarm = open.get();
            boolean changed = false;
            if (value != null && !value.equals(alarm.getValue())) {
                alarm.setValue(value);
                changed = true;
            }
            // A sensor moved to another block: access now goes through the new one.
            if (deviceId != null && !deviceId.equals(alarm.getDeviceId())) {
                alarm.setDeviceId(deviceId);
                changed = true;
            }
            if (changed) {
                repository.update(alarm);
            }
            return false;
        }
        Alarm alarm = new Alarm();
        alarm.setChipId(chipId);
        alarm.setDeviceId(deviceId);
        alarm.setAlarmType(type);
        alarm.setStartedAt(startedAt != null ? startedAt : Instant.now());
        alarm.setValue(value);
        try {
            repository.save(alarm);
        } catch (Exception e) {
            // The MQTT thread and the scheduled check raced; idx_alarm_open kept one episode.
            log.debug("Alarm {} for {} not opened: {}", type, chipId, e.getMessage());
            return false;
        }
        log.info("Alarm {} opened for {} (device {})", type, chipId, deviceId);
        notify("open", alarm);
        return true;
    }

    /** Closes the open episode of this type, if any. Returns true if one was closed. */
    public boolean clear(Long chipId, String type, Instant endedAt) {
        Optional<Alarm> open = repository.findFirstByChipIdAndAlarmTypeAndEndedAtIsNull(chipId, type);
        if (open.isEmpty()) {
            return false;
        }
        Alarm alarm = open.get();
        Instant end = endedAt != null ? endedAt : Instant.now();
        alarm.setEndedAt(end.isBefore(alarm.getStartedAt()) ? alarm.getStartedAt() : end);
        repository.update(alarm);
        log.info("Alarm {} closed for {} (device {})", type, chipId, alarm.getDeviceId());
        notify("close", alarm);
        return true;
    }

    /**
     * Live (non-retained) data of a sensor: it transmits again, so a gap ends; the moisture
     * reading decides the LEVEL alarm and the battery voltage the BATTERY alarm. deviceId is
     * the device the data came through.
     */
    public void onSensorData(Long deviceId, Long chipId, Integer moistureAdc, Float battery) {
        if (deviceId == null || chipId == null) {
            return;
        }
        Instant now = Instant.now();
        clear(chipId, GAP, now);
        Sensor sensor = sensorRepository.findByDeviceIdAndSensorChipId(deviceId, chipId).orElse(null);
        if (sensor == null) {
            return;
        }
        if (battery != null) {
            updateBattery(sensor, deviceId, chipId, battery, now);
        }
        if (moistureAdc != null) {
            if (notImmersed(sensor, moistureAdc)) {
                raise(chipId, deviceId, LEVEL, now, moistureAdc.doubleValue());
            } else {
                clear(chipId, LEVEL, now);
            }
        }
    }

    private void updateBattery(Sensor sensor, Long deviceId, Long chipId, float battery, Instant now) {
        int thresholdMv = sensor.getWarningVoltage() != null
                ? sensor.getWarningVoltage()
                : sensor.getBatteryType() != null
                        ? sensor.getBatteryType().getWarningVoltageMv()
                        : BatteryType.ALKALINE.getWarningVoltageMv();
        int batteryMv = Math.round(battery * 1000f);
        if (batteryMv <= thresholdMv) {
            raise(chipId, deviceId, BATTERY, now, (double) battery);
        } else if (batteryMv > thresholdMv + 200 || batteryMv >= 3000) {
            clear(chipId, BATTERY, now);
        }
    }

    /**
     * Same rule as immersionWarning() in client/src/utils/moisture.js: the reading is above the
     * middle between air (value1) and dry soil (value2). Only moisture sensors (type 0).
     */
    boolean notImmersed(Sensor sensor, int adc) {
        Short type = sensor.getSensorType();
        if (type != null && type.shortValue() != 0) {
            return false;
        }
        Integer air;
        Integer dry;
        SensorCalibration cal = calibrationService.bySensorId(sensor.getId());
        if (cal != null) {
            air = cal.getValue1();
            dry = cal.getValue2();
        } else {
            SensorCalibrationService.Values d = calibrationService.defaultsFor(type);
            air = d.value1();
            dry = d.value2();
        }
        if (air == null || dry == null) {
            return false;
        }
        return adc > air - (air - dry) / 2.0;
    }

    /** STATUS of a device changed. Only blocks get OFFLINE alarms. */
    public void onDeviceStatus(Long deviceId, String status) {
        if ("online".equals(status)) {
            clear(deviceId, OFFLINE, Instant.now());
        } else if ("offline".equals(status) && isBlock(deviceId)) {
            raise(deviceId, deviceId, OFFLINE, Instant.now(), null);
        }
    }

    private boolean isBlock(Long chipId) {
        return deviceRepository.findById(chipId)
                .map(d -> "BLOCK".equals(d.getDeviceType()))
                .orElse(false);
    }

    /**
     * Periodic reconciliation: raises gaps (there is no message to react to when a sensor is
     * silent), and fixes what the live hooks missed — alarms of sensors that left their block,
     * block states restored after a restart.
     */
    public void mqttConnected() {
        mqttUpSince = Instant.now();
    }

    public void mqttLost() {
        mqttUpSince = null;
    }

    @Scheduled(fixedDelay = "60s", initialDelay = "30s")
    public void check() {
        Instant now = Instant.now();
        Instant upSince = mqttUpSince;
        if (upSince != null && !now.isBefore(upSince.plusSeconds(GAP_SETTLE_SECONDS))) {
            try {
                checkGaps(now);
            } catch (Exception e) {
                log.warn("Sensor gap check failed: {}", e.getMessage());
            }
        }
        try {
            checkBlocks();
        } catch (Exception e) {
            log.warn("Block offline check failed: {}", e.getMessage());
        }
    }

    void checkGaps(Instant now) {
        // Active sensors as in the sensor catalog: a sensor registered in a block slot belongs
        // to that block; otherwise a standalone sensor device is its own access device.
        Map<Long, Set<Long>> accessDevices = new HashMap<>();
        for (SensorRegistration reg : registrationRepository.findAll()) {
            Long chip = reg.getSensorChipId();
            if (chip != null && chip > 0 && reg.getDeviceId() != null) {
                accessDevices.computeIfAbsent(chip, k -> new LinkedHashSet<>()).add(reg.getDeviceId());
            }
        }
        for (Device d : deviceRepository.findByDeviceType("INDIVIDUAL_SENSOR")) {
            accessDevices.computeIfAbsent(d.getChipId(), k -> new LinkedHashSet<>(Set.of(d.getChipId())));
        }

        // Freshest data row of every active sensor, among rows of its access devices.
        Map<Long, Sensor> latest = new HashMap<>();
        for (Sensor s : sensorRepository.findAll()) {
            Long chip = s.getSensorChipId() != null ? s.getSensorChipId() : s.getDeviceId();
            Set<Long> devices = accessDevices.get(chip);
            if (devices == null || s.getLastTime() == null || !devices.contains(s.getDeviceId())) {
                continue;
            }
            Sensor current = latest.get(chip);
            if (current == null || s.getLastTime().isAfter(current.getLastTime())) {
                latest.put(chip, s);
            }
        }

        for (Map.Entry<Long, Sensor> e : latest.entrySet()) {
            Long chip = e.getKey();
            Sensor s = e.getValue();
            Integer send = s.getSend();
            if (send == null || send <= 0) {
                continue;
            }
            Instant due = s.getLastTime().plusSeconds(GAP_MISSED_SENDS * (long) send + GAP_GRACE_SECONDS);
            if (now.isAfter(due)) {
                raise(chip, s.getDeviceId(), GAP, due, null);
            } else {
                clear(chip, GAP, now);
            }
        }

        // A sensor that is no longer registered anywhere can neither transmit nor be lifted
        // out of the soil any more: close what it left open.
        for (String type : List.of(GAP, LEVEL, BATTERY)) {
            for (Alarm open : repository.findByAlarmTypeAndEndedAtIsNull(type)) {
                if (!accessDevices.containsKey(open.getChipId())) {
                    clear(open.getChipId(), type, now);
                }
            }
        }
    }

    void checkBlocks() {
        for (Device block : deviceRepository.findByDeviceType("BLOCK")) {
            String status = block.getLastStatus();
            if ("offline".equals(status)) {
                raise(block.getChipId(), block.getChipId(), OFFLINE,
                        block.getLastStatusAt() != null ? block.getLastStatusAt() : Instant.now(), null);
            } else if ("online".equals(status)) {
                clear(block.getChipId(), OFFLINE, Instant.now());
            }
        }
    }

    private void notify(String event, Alarm alarm) {
        try {
            broadcaster.broadcast(Map.of(
                    "type", "alarm",
                    "event", event,
                    "alarmType", alarm.getAlarmType(),
                    "deviceId", alarm.getDeviceId(),
                    "chipId", alarm.getChipId(),
                    "timestamp", System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("Alarm broadcast failed: {}", e.getMessage());
        }
    }
}
