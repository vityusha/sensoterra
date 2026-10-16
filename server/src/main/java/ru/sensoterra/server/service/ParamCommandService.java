package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.CommandLog;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.CommandLogRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Optional;

@Singleton
public class ParamCommandService {

    private final MqttClientService mqttClientService;
    private final RelayRepository relayRepository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository sensorRegistrationRepository;
    private final DeviceRepository deviceRepository;
    private final CommandLogRepository commandLogRepository;

    public ParamCommandService(MqttClientService mqttClientService,
                               RelayRepository relayRepository,
                               SensorRepository sensorRepository,
                               SensorRegistrationRepository sensorRegistrationRepository,
                               DeviceRepository deviceRepository,
                               CommandLogRepository commandLogRepository) {
        this.mqttClientService = mqttClientService;
        this.relayRepository = relayRepository;
        this.sensorRepository = sensorRepository;
        this.sensorRegistrationRepository = sensorRegistrationRepository;
        this.deviceRepository = deviceRepository;
        this.commandLogRepository = commandLogRepository;
    }

    public CommandResult relayParams(Long relayId, RelayParamsRequest request) {
        Relay relay = relayRepository.findById(relayId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Relay not found"));
        if (!mqttClientService.isConnected()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT broker not connected");
        }

        Long sensorId = request.getIdSensor() != null ? request.getIdSensor() : relay.getSensorId();
        if (sensorId != null && sensorId != 0) {
            boolean registered = sensorRegistrationRepository
                    .findByDeviceIdAndSensorChipIdIsNotNull(relay.getDeviceId())
                    .stream()
                    .anyMatch(r -> sensorId.equals(r.getSensorChipId()));
            if (!registered) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                        "Sensor " + sensorId + " is not registered for block " + relay.getDeviceId()
                                + ". Only sensors from the block registration list can be attached.");
            }
        }

        String topic = relay.getDeviceId() + "/PARAM_R" + relay.getRelayNumber();
        String payload = request.toPayload(relay, mqttClientService.firmwareTime(relay.getDeviceId()));

        boolean sent = mqttClientService.publish(topic, payload);
        if (!sent) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to publish relay params");
        }

        commandLogRepository.save(log(relay.getDeviceId(), "RELAY_PARAM", payload, topic));
        return new CommandResult(relay.getDeviceId(), relay.getRelayNumber(), "RELAY_PARAM", true);
    }

    public CommandResult sensorParams(Long sensorId, SensorParamsRequest request) {
        Sensor sensor = sensorRepository.findById(sensorId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Sensor not found"));

        String topic;
        String payload;
        Long logDeviceId;

        Device individual = deviceRepository.findByChipId(sensor.getSensorChipId()).orElse(null);
        boolean standalone = individual != null && "INDIVIDUAL_SENSOR".equals(individual.getDeviceType());
        if (sensor.getSensorNumber() != null) {
            topic = sensor.getDeviceId() + "/PARAM_S" + sensor.getSensorNumber();
            payload = request.toBlockPayload(sensor, mqttClientService.firmwareTime(sensor.getDeviceId()));
            logDeviceId = sensor.getDeviceId();
        } else if (standalone) {
            topic = sensor.getSensorChipId() + "/Sleep_Send";
            payload = request.toSleepSendPayload(sensor);
            logDeviceId = individual.getChipId();
        } else {
            throw new HttpStatusException(HttpStatus.CONFLICT,
                    "Sensor slot number unknown for block-attached sensor");
        }

        if (!mqttClientService.isConnected()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT broker not connected");
        }

        // Автономные датчики спят: без retain сообщение теряется между их пробуждениями.
        // Retained снимется после первого VALUES с применёнными настройками (confirmSensorParams).
        boolean sent = standalone
                ? mqttClientService.publishRetained(topic, payload)
                : mqttClientService.publish(topic, payload);
        if (!sent) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to publish sensor params");
        }

        commandLogRepository.save(log(logDeviceId, "SENSOR_PARAM", payload, topic));
        return new CommandResult(logDeviceId, null, "SENSOR_PARAM", true);
    }

    private CommandLog log(Long deviceId, String type, String payload, String topic) {
        CommandLog commandLog = new CommandLog();
        commandLog.setDeviceId(deviceId);
        commandLog.setCommandType(type);
        commandLog.setPayload(payload);
        commandLog.setTopic(topic);
        commandLog.setSentAt(Instant.now());
        commandLog.setConfirmed(false);
        return commandLog;
    }

    @Serdeable
    public record CommandResult(Long deviceId, Short relayNumber, String commandType, Boolean sent) {
    }

    @Serdeable
    public static class RelayParamsRequest {
        private String name;
        private Boolean activate;
        private Integer type;
        private Boolean useTimer;
        private Integer dataType;
        private Integer turnOnTimeFirst;
        private Integer worktimeFirst;
        private Integer turnOnTimeSecond;
        private Integer worktimeSecond;
        private Integer betweentime;
        private Long idSensor;
        private Integer startTime;
        private Integer finishTime;
        private Integer limitSingle;
        private Integer limitLow;
        private Integer limitHigh;

        public String toPayload(Relay relay, String timestr) {
            // Позиционный формат прошивки, ровно 17 полей через ';':
            // name;activate;type;use_timer;data_type;turn_on_time_first;worktime_first;
            // turn_on_time_second;worktime_second;betweentime;id_sensor;start_time;finish_time;
            // limit_single;limit_low;limit_high;timestr
            // Пустые поля прошивка трактует как "оставить по умолчанию" (strlen()==0).
            java.util.List<String> fields = new java.util.ArrayList<>();
            fields.add(sv(firstNonNull(name, relay.getName())));
            fields.add(sv(requestOrCurrent(activate, relay.getActivity())));
            fields.add(sv(type != null ? type : typeToInt(relay.getTypeEnum())));
            fields.add(sv(toInt(useTimer, relay.getTimerEnabled())));
            fields.add(sv(firstNonNull(dataType, relay.getDataType())));
            fields.add(sv(firstNonNull(turnOnTimeFirst, timeMinutes(relay.getMainTime()))));
            fields.add(sv(firstNonNull(worktimeFirst, relay.getDurationMain())));
            fields.add(sv(firstNonNull(turnOnTimeSecond, timeMinutes(relay.getExtraTime()))));
            fields.add(sv(firstNonNull(worktimeSecond, relay.getDurationExtra())));
            fields.add(sv(firstNonNull(betweentime, relay.getIntervalH())));
            fields.add(sv(firstNonNull(idSensor, relay.getSensorId())));
            fields.add(sv(firstNonNull(startTime, timeMinutes(relay.getStartTime()))));
            fields.add(sv(firstNonNull(finishTime, timeMinutes(relay.getFinishTime()))));
            fields.add(sv(limitSingle != null ? limitSingle : relay.getOneLimit()));
            fields.add(sv(limitLow != null ? limitLow : relay.getLowLimit()));
            fields.add(sv(limitHigh != null ? limitHigh : relay.getHighLimit()));
            fields.add(timestr);
            return String.join(";", fields);
        }

        private String sv(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private Object requestOrCurrent(Boolean request, Boolean current) {
            return request == null
                    ? (current == null ? null : (current ? 1 : 0))
                    : (request ? 1 : 0);
        }

        private Object firstNonNull(Object override, Object fallback) {
            return override != null ? override : fallback;
        }

        private static Integer timeMinutes(java.time.LocalTime time) {
            return time == null ? null : time.toSecondOfDay() / 60;
        }

        private Integer typeToInt(String typeEnum) {
            if (typeEnum == null) return 0;
            return switch (typeEnum) {
                case "S_ONE" -> 1;
                case "S_LOW" -> 2;
                case "S_HIGH" -> 3;
                case "TIME" -> 0;
                default -> 0;
            };
        }

        private Integer toInt(Boolean req, Boolean cur) {
            if (req != null) {
                return req ? 1 : 0;
            }
            return cur != null ? (cur ? 1 : 0) : null;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Boolean getActivate() { return activate; }
        public void setActivate(Boolean activate) { this.activate = activate; }
        public Integer getType() { return type; }
        public void setType(Integer type) { this.type = type; }
        public Boolean getUseTimer() { return useTimer; }
        public void setUseTimer(Boolean useTimer) { this.useTimer = useTimer; }
        public Integer getDataType() { return dataType; }
        public void setDataType(Integer dataType) { this.dataType = dataType; }
        public Integer getTurnOnTimeFirst() { return turnOnTimeFirst; }
        public void setTurnOnTimeFirst(Integer turnOnTimeFirst) { this.turnOnTimeFirst = turnOnTimeFirst; }
        public Integer getWorktimeFirst() { return worktimeFirst; }
        public void setWorktimeFirst(Integer worktimeFirst) { this.worktimeFirst = worktimeFirst; }
        public Integer getTurnOnTimeSecond() { return turnOnTimeSecond; }
        public void setTurnOnTimeSecond(Integer turnOnTimeSecond) { this.turnOnTimeSecond = turnOnTimeSecond; }
        public Integer getWorktimeSecond() { return worktimeSecond; }
        public void setWorktimeSecond(Integer worktimeSecond) { this.worktimeSecond = worktimeSecond; }
        public Integer getBetweentime() { return betweentime; }
        public void setBetweentime(Integer betweentime) { this.betweentime = betweentime; }
        public Long getIdSensor() { return idSensor; }
        public void setIdSensor(Long idSensor) { this.idSensor = idSensor; }
        public Integer getStartTime() { return startTime; }
        public void setStartTime(Integer startTime) { this.startTime = startTime; }
        public Integer getFinishTime() { return finishTime; }
        public void setFinishTime(Integer finishTime) { this.finishTime = finishTime; }
        public Integer getLimitSingle() { return limitSingle; }
        public void setLimitSingle(Integer limitSingle) { this.limitSingle = limitSingle; }
        public Integer getLimitLow() { return limitLow; }
        public void setLimitLow(Integer limitLow) { this.limitLow = limitLow; }
        public Integer getLimitHigh() { return limitHigh; }
        public void setLimitHigh(Integer limitHigh) { this.limitHigh = limitHigh; }
    }

    @Serdeable
    public static class SensorParamsRequest {
        private String name;
        private Integer sleep;
        private Integer send;
        private Integer percentN;
        private Integer percentS;
        private Integer minVoltage;

        public String toBlockPayload(Sensor sensor, String timestr) {
            // Позиционный формат прошивки блока, ровно 7 полей через ';':
            // name;sleep;send;percent_n;percent_s;min_voltage;timestr
            java.util.List<String> fields = new java.util.ArrayList<>();
            fields.add(sv(firstNonNull(name, sensor.getName())));
            fields.add(sv(sleep != null ? sleep : sensor.getSleep()));
            fields.add(sv(send != null ? send : sensor.getSend()));
            fields.add(sv(percentN != null ? percentN : nullToInt(sensor.getPercentN())));
            fields.add(sv(percentS != null ? percentS : nullToInt(sensor.getPercentS())));
            fields.add(sv(minVoltage != null ? minVoltage : sensor.getMinVoltage()));
            fields.add(timestr);
            return String.join(";", fields);
        }

        public String toSleepSendPayload(Sensor sensor) {
            // Индивидуальный датчик: sleep;send (старые прошивки без LN/LS/minV)
            // или sleep;send;percent_n;percent_s;min_voltage для новых.
            Integer pn = percentN != null ? percentN : nullToInt(sensor.getPercentN());
            Integer ps = percentS != null ? percentS : nullToInt(sensor.getPercentS());
            Integer mv = minVoltage != null ? minVoltage : sensor.getMinVoltage();
            java.util.List<String> fields = new java.util.ArrayList<>();
            fields.add(sv(sleep != null ? sleep : sensor.getSleep()));
            fields.add(sv(send != null ? send : sensor.getSend()));
            if (pn != null || ps != null || mv != null) {
                fields.add(sv(pn));
                fields.add(sv(ps));
                fields.add(sv(mv));
            }
            return String.join(";", fields);
        }

        private String sv(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private Integer nullToInt(Short value) {
            return value == null ? null : value.intValue();
        }

        private Object firstNonNull(Object override, Object fallback) {
            return override != null ? override : fallback;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getSleep() { return sleep; }
        public void setSleep(Integer sleep) { this.sleep = sleep; }
        public Integer getSend() { return send; }
        public void setSend(Integer send) { this.send = send; }
        public Integer getPercentN() { return percentN; }
        public void setPercentN(Integer percentN) { this.percentN = percentN; }
        public Integer getPercentS() { return percentS; }
        public void setPercentS(Integer percentS) { this.percentS = percentS; }
        public Integer getMinVoltage() { return minVoltage; }
        public void setMinVoltage(Integer minVoltage) { this.minVoltage = minVoltage; }
    }
}