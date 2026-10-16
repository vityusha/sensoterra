package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.CommandLog;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.CommandLogRepository;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Optional;

@Singleton
public class RelayCommandConfirmationService {

    private static final String RELAY_CONTROL = "RELAY_CONTROL";
    private static final String RELAY_PARAM = "RELAY_PARAM";
    private static final String SENSOR_PARAM = "SENSOR_PARAM";

    private final CommandLogRepository commandLogRepository;
    private final Provider<MqttClientService> mqttClientServiceProvider;

    public RelayCommandConfirmationService(CommandLogRepository commandLogRepository,
                                           Provider<MqttClientService> mqttClientServiceProvider) {
        this.commandLogRepository = commandLogRepository;
        this.mqttClientServiceProvider = mqttClientServiceProvider;
    }

    public void confirm(Long deviceId, int relayNumber, int state) {
        commandLogRepository
                .findFirstByDeviceIdAndCommandTypeAndConfirmedFalseOrderBySentAtDesc(
                        deviceId, RELAY_CONTROL)
                .filter(log -> matchesControl(log, relayNumber, state))
                .ifPresent(this::confirmLog);
    }

    public void confirmRelayParams(Long deviceId, int relayNumber) {
        commandLogRepository
                .findFirstByDeviceIdAndCommandTypeAndConfirmedFalseOrderBySentAtDesc(
                        deviceId, RELAY_PARAM)
                .filter(log -> firstNum(log, relayNumber))
                .ifPresent(this::confirmLog);
    }

    /**
     * Подтверждение настроек датчика по его же VALUES.
     *
     * Retained снимается даже при несовпадении значений: датчик уже проснулся и сообщил
     * о себе, то есть сообщение дошло и больше не нужно. Иначе старые настройки остаются
     * на брокере и при следующем пробуждении возвращаются поверх тех, что человек задал
     * руками (жалоба на 14288428: «после ручной настройки вернулись прежние»).
     */
    public void confirmSensorParams(Long deviceId, Integer sleep, Integer send,
                                    Integer percentN, Integer percentS, Integer minVoltage) {
        commandLogRepository
                .findFirstByDeviceIdAndCommandTypeAndConfirmedFalseOrderBySentAtDesc(
                        deviceId, SENSOR_PARAM)
                .ifPresent(log -> {
                    if (matchesSensor(log, sleep, send, percentN, percentS, minVoltage)) {
                        confirmLog(log);
                    } else {
                        maybeClearRetainedStandalone(log);
                    }
                });
    }

    private void confirmLog(CommandLog log) {
        log.setConfirmed(true);
        commandLogRepository.update(log);
        maybeClearRetainedStandalone(log);
    }

    /**
     * Для автономных датчиков Sleep_Send публикуется retain=true — иначе спящий
     * датчик сообщение пропустит. Как только датчик применил и сообщил (VALUES),
     * retained нужно снять, чтобы настройка не переприменялась при каждом пробуждении.
     */
    private void maybeClearRetainedStandalone(CommandLog log) {
        if (!SENSOR_PARAM.equals(log.getCommandType())) {
            return;
        }
        String[] p = log.getPayload().split(";", -1);
        boolean standalone = p.length == 2 || p.length == 5;
        if (!standalone) {
            return;
        }
        mqttClientServiceProvider.get().clearRetained(log.getDeviceId() + "/Sleep_Send");
    }

    private boolean matchesControl(CommandLog log, int relayNumber, int state) {
        try {
            String[] parts = log.getPayload().split(";", -1);
            return parts.length >= 2
                    && Integer.parseInt(parts[0].trim()) == relayNumber
                    && Integer.parseInt(parts[1].trim()) == state;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean firstNum(CommandLog log, int relayNumber) {
        try {
            String[] parts = log.getPayload().split(";", -1);
            return parts.length >= 1 && Integer.parseInt(parts[0].trim()) == relayNumber;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesSensor(CommandLog log, Integer sleep, Integer send,
                                  Integer percentN, Integer percentS, Integer minVoltage) {
        String[] p = log.getPayload().split(";", -1);
        if (p.length >= 7) {
            // Block-attached: name;sleep;send;percent_n;percent_s;min_voltage;timestr
            return matches(p[1], sleep) && matches(p[2], send)
                    && matches(p[3], percentN) && matches(p[4], percentS)
                    && matches(p[5], minVoltage);
        }
        if (p.length >= 5) {
            // Individual: sleep;send;percent_n;percent_s;min_voltage
            return matches(p[0], sleep) && matches(p[1], send)
                    && matches(p[2], percentN) && matches(p[3], percentS)
                    && matches(p[4], minVoltage);
        }
        if (p.length >= 2) {
            // Old individual firmware: только sleep;send
            return matches(p[0], sleep) && matches(p[1], send);
        }
        return false;
    }

    /**
     * Сверка одного поля. Датчик сообщает не всё, что мы отправляли: например, прошивка
     * 1.2.6 у 14288428 не присылает MIN_V вовсе. Отсутствующее поле — не расхождение,
     * иначе подтверждение не наступало бы никогда и retained жил бы на брокере вечно.
     */
    private boolean matches(String sent, Integer received) {
        if (sent == null || sent.trim().isEmpty() || received == null) {
            return true;
        }
        try {
            return Integer.parseInt(sent.trim()) == received;
        } catch (Exception e) {
            return false;
        }
    }
}