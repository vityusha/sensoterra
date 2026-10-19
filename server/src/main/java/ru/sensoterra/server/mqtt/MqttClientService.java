package ru.sensoterra.server.mqtt;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.service.DeviceStateStore;
import ru.sensoterra.server.service.DeviceZoneService;
import ru.sensoterra.server.service.MqttMessageProcessor;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Context
public class MqttClientService {

    private static final Logger log = LoggerFactory.getLogger(MqttClientService.class);

    /**
     * Суффиксы топиков, которые прошивка публикует с retain=true. Часть из них
     * (Sleep_Send, PARAM_*) приходит retained только как мусор от других клиентов,
     * но сбросить их тоже нужно, иначе брокер продолжит их отдавать.
     */
    private static final List<String> STANDARD_SUFFIXES = buildStandardSuffixes();

    private static final int MAX_TRACKED_DEVICES = 2000;
    private static final int MAX_TOPICS_PER_DEVICE = 128;

    private final MqttConfiguration config;
    private final MqttMessageProcessor messageProcessor;
    private final DeviceStateStore stateStore;
    private final DeviceRepository deviceRepository;
    private final DeviceZoneService deviceZoneService;

    private final Map<Long, Set<String>> knownTopics = new ConcurrentHashMap<>();
    /** Retained-сообщения, реально пришедшие от брокера: по ним судим, было ли что снимать. */
    private final Set<String> retainedSeen = ConcurrentHashMap.newKeySet();

    private MqttClient client;

    private static List<String> buildStandardSuffixes() {
        List<String> suffixes = new ArrayList<>(List.of("STATUS", "VALUES", "SENSORS", "PING", "Sleep_Send"));
        for (int i = 1; i <= 8; i++) {
            suffixes.add("EVENT_R" + i);
            suffixes.add("STATE_R" + i);
            suffixes.add("PARAM_R" + i);
            suffixes.add("EVENT_S" + i);
            suffixes.add("PARAM_S" + i);
        }
        return List.copyOf(suffixes);
    }

    public MqttClientService(MqttConfiguration config,
                             MqttMessageProcessor messageProcessor,
                             DeviceStateStore stateStore,
                             DeviceRepository deviceRepository,
                             DeviceZoneService deviceZoneService) {
        this.config = config;
        this.messageProcessor = messageProcessor;
        this.stateStore = stateStore;
        this.deviceRepository = deviceRepository;
        this.deviceZoneService = deviceZoneService;
    }

    /**
     * Запоминает топик, реально приходивший от устройства, чтобы при удалении
     * устройства можно было сбросить и нестандартные retained-топики вроде
     * STATE_R88 или RELAY_R1, которых нет в списке прошивки.
     */
    private void trackTopic(String topic) {
        int slash = topic.indexOf('/');
        if (slash <= 0 || slash == topic.length() - 1) {
            return;
        }
        Long deviceId;
        try {
            deviceId = Long.parseLong(topic.substring(0, slash));
        } catch (NumberFormatException e) {
            return;
        }
        if (!knownTopics.containsKey(deviceId) && knownTopics.size() >= MAX_TRACKED_DEVICES) {
            return;
        }
        Set<String> suffixes = knownTopics.computeIfAbsent(deviceId, id -> ConcurrentHashMap.newKeySet());
        if (suffixes.size() < MAX_TOPICS_PER_DEVICE) {
            suffixes.add(topic.substring(slash + 1));
        }
    }

    public Set<String> getKnownTopicSuffixes(Long deviceId) {
        return Set.copyOf(knownTopics.getOrDefault(deviceId, Set.of()));
    }

    /**
     * Сбрасывает retained на всех топиках устройства: и на стандартных из прошивки,
     * и на всех, что сервер реально видел от этого устройства.
     */
    public RetainedClearResult clearRetainedForDevice(Long deviceId) {
        Set<String> suffixes = new LinkedHashSet<>(STANDARD_SUFFIXES);
        suffixes.addAll(getKnownTopicSuffixes(deviceId));
        int cleared = 0;
        for (String suffix : suffixes) {
            if (clearRetained(deviceId + "/" + suffix)) {
                cleared++;
            }
        }
        knownTopics.remove(deviceId);
        if (cleared < suffixes.size()) {
            log.warn("Cleared retained on {}/{} topics for device {}",
                    cleared, suffixes.size(), deviceId);
        }
        return new RetainedClearResult(suffixes.size(), cleared);
    }

    /**
     * Снять retained только с топиков настроек: Sleep_Send и PARAM_*.
     *
     * Отдельный метод, а не clearRetainedForDevice: там чистятся и VALUES/STATUS, которые
     * прошивка публикует сама, — трогать их руками не нужно. Нужен там, где retained со
     * Sleep_Send пережил применение и вернул старые настройки поверх новых.
     */
    public RetainedClearResult clearRetainedSettings(Long deviceId) {
        Set<String> suffixes = new LinkedHashSet<>();
        suffixes.add("Sleep_Send");
        for (int i = 1; i <= 8; i++) {
            suffixes.add("PARAM_S" + i);
            suffixes.add("PARAM_R" + i);
        }
        for (String suffix : getKnownTopicSuffixes(deviceId)) {
            if (suffix.startsWith("PARAM_") || suffix.equals("Sleep_Send")) {
                suffixes.add(suffix);
            }
        }
        int cleared = 0;
        for (String suffix : suffixes) {
            if (clearRetained(deviceId + "/" + suffix)) {
                cleared++;
            }
        }
        log.info("Cleared retained settings on {}/{} topics for device {}", cleared, suffixes.size(), deviceId);
        return new RetainedClearResult(suffixes.size(), cleared);
    }

    @Serdeable
    @Introspected
    public record RetainedClearResult(int total, int cleared) {
    }

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(config.getBroker(), config.getClientId());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setKeepAliveInterval(config.getKeepAliveInterval());
            options.setConnectionTimeout(config.getConnectionTimeout());
            options.setAutomaticReconnect(true);

            if (config.getUsername() != null && !config.getUsername().isEmpty()) {
                options.setUserName(config.getUsername());
            }
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                options.setPassword(config.getPassword().toCharArray());
            }
            if (hasStatusTopic()) {
                options.setWill(config.getStatusTopic(), "offline".getBytes(), 1, true);
            }

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT connected to {}", serverURI);
                    publishServerOnline();
                    subscribe();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT connection lost: {}", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    if (hasStatusTopic() && topic.equals(config.getStatusTopic())) {
                        return;
                    }
                    boolean retained = message.isRetained();
                    trackTopic(topic);
                    if (retained) {
                        retainedSeen.add(topic);
                    }
                    if (retained || topic.endsWith("/STATUS") || topic.endsWith("/PING")) {
                        log.debug("MQTT retained={} topic={} payload={}", retained, topic, new String(message.getPayload()));
                    }
                    messageProcessor.process(topic, new String(message.getPayload()), retained);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(options);
        } catch (MqttException e) {
            log.error("Failed to connect to MQTT broker {}", config.getBroker(), e);
        }
    }

    private boolean hasStatusTopic() {
        return config.getStatusTopic() != null && !config.getStatusTopic().isEmpty();
    }

    /** Retained online поверх LWT offline — по топику видно, жив ли сервер. */
    private void publishServerOnline() {
        if (!hasStatusTopic()) {
            return;
        }
        try {
            client.publish(config.getStatusTopic(), "online".getBytes(), 1, true);
        } catch (MqttException e) {
            log.warn("Failed to publish server status to {}: {}", config.getStatusTopic(), e.getMessage());
        }
    }

    private void subscribe() {
        try {
            client.subscribe("#", 1);
            log.info("Subscribed to topic # on {}", config.getBroker());
        } catch (MqttException e) {
            log.error("Failed to subscribe to topic # on {}", config.getBroker(), e);
        }
    }

    public boolean publishRelayCommand(Long deviceId, int relayNumber, int state) {
        if (!isConnected()) {
            log.warn("MQTT not connected, cannot send relay command to {}", deviceId);
            return false;
        }
        String topic = deviceId + "/CONTROL_R";
        String payload = relayNumber + ";" + state + ";" + firmwareTime(deviceId);
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            client.publish(topic, bytes, 1, false);
            log.info("Published relay command to {}: {}", topic, payload);
            return true;
        } catch (MqttException e) {
            log.error("Failed to publish relay command to {}", topic, e);
            return false;
        }
    }

    public String firmwareTime(Long deviceId) {
        // Прошивка применяет PARAM_R/PARAM_S только при timeBoard < timeMQTT, где
        // timeBoard — часы платы реле. Они синхронизируются с NTP и переписываются
        // лишь при расхождении > 5 минут, поэтому реальный уход до ~5 минут штатен.
        // Запас в 1 минуту делал команды для отстающих/спешащих плат лотереей
        // (блок 3282079: RTC спешит ~на минуту, все настройки молча игнорировались).
        return ZonedDateTime.now(deviceZoneService.zoneFor(deviceId))
                .plusMinutes(6)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    /**
     * Удаление регистрации датчика на блоке удалённо (новая прошивка): блок подписан
     * на топик blockId/UNREG и снимает датчик со своего слота, если payload вида
     * "sensorChipId;timestr" актуален по времени. Затем блок переопубликовывает SENSORS.
     */
    public boolean publishUnregister(Long blockId, Long sensorChipId) {
        if (!isConnected()) {
            log.warn("MQTT not connected, cannot send UNREG to {}", blockId);
            return false;
        }
        String topic = blockId + "/UNREG";
        String payload = sensorChipId + ";" + firmwareTime(blockId);
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
            log.info("Published UNREG to {}: {}", topic, payload);
            return true;
        } catch (MqttException e) {
            log.error("Failed to publish UNREG to {}", topic, e);
            return false;
        }
    }

    public boolean publish(String topic, String payload) {
        if (!isConnected()) {
            log.warn("MQTT not connected, cannot publish to {}", topic);
            return false;
        }
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
            log.info("Published to {}: {}", topic, payload);
            return true;
        } catch (MqttException e) {
            log.error("Failed to publish to {}", topic, e);
            return false;
        }
    }

    /**
     * Публикация с retain=true: держит сообщение на брокере, пока оно не будет
     * переопубликовано или удалено. Нужно для спящих автономных датчиков
     * (Sleep_Send): они подключаются редко, и без retain сообщение теряется.
     */
    public boolean publishRetained(String topic, String payload) {
        if (!isConnected()) {
            log.warn("MQTT not connected, cannot publish retained to {}", topic);
            return false;
        }
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true);
            log.info("Published retained to {}: {}", topic, payload);
            return true;
        } catch (MqttException e) {
            log.error("Failed to publish retained to {}", topic, e);
            return false;
        }
    }

    /**
     * Удаляет retained-сообщение с топика: публикация пустого payload с retain=true.
     * Если retained на топике не было, публикация безвредна, но создаёт пустое сообщение.
     */
    /**
     * Публикует пустой payload с retain=true, и MQTT-брокер удаляет сохранённое сообщение.
     * Возвращает true, если на топике действительно было сохранённое сообщение, — чтобы
     * вызывающий код не отвечал «снято» в никуда. Само наличие брокер не сообщает, поэтому
     * опираемся на retained-сообщения, которые сервер уже увидел при подписке.
     */
    public boolean clearRetained(String topic) {
        if (!isConnected()) {
            log.warn("MQTT not connected, cannot clear retained on {}", topic);
            return false;
        }
        try {
            client.publish(topic, new byte[0], 1, true);
            boolean had = retainedSeen.remove(topic);
            log.info("Cleared retained on {} (had message: {})", topic, had);
            return had;
        } catch (MqttException e) {
            log.error("Failed to clear retained on {}", topic, e);
            return false;
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public boolean isConnected(Device device) {
        return isConnected();
    }

    public String getLastStatus() {
        return stateStore.getLastStatus();
    }

    public String getDeviceStatus(Long deviceId) {
        return stateStore.getStatus(deviceId);
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            log.error("Error disconnecting from MQTT", e);
        }
    }
}