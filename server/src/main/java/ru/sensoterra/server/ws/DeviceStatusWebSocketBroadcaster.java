package ru.sensoterra.server.ws;

import ru.sensoterra.server.service.DeviceStateStore;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Singleton
public class DeviceStatusWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusWebSocketBroadcaster.class);

    private final DeviceStateStore stateStore;

    public DeviceStatusWebSocketBroadcaster(DeviceStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Рассылает событие только тем сессиям, которым доступны упомянутые в нём устройства.
     * В сообщениях устройством считается deviceId и, если есть, chipId датчика.
     */
    public void broadcast(Map<String, Object> message) {
        Set<Long> chips = chipsOf(message);
        for (WebSocketSession session : DeviceStatusWebSocket.getSessions()) {
            if (!session.isOpen()) {
                continue;
            }
            DeviceStatusWebSocket.SessionInfo info = DeviceStatusWebSocket.info(session);
            if (info == null || !info.canSee(chips)) {
                continue;
            }
            session.sendAsync(message);
        }
    }

    private Set<Long> chipsOf(Map<String, Object> message) {
        Set<Long> chips = new HashSet<>();
        addChip(chips, message.get("deviceId"));
        addChip(chips, message.get("chipId"));
        return chips;
    }

    private void addChip(Set<Long> chips, Object value) {
        if (value instanceof Number n) {
            chips.add(n.longValue());
        }
    }

    /** Начальное уведомление о состоянии MQTT-подключения — без данных устройств. */
    public void broadcastCurrentStatus(WebSocketSession session) {
        if (session.isOpen()) {
            var message = Map.of(
                    "type", "status",
                    "status", stateStore.getLastStatus(),
                    "timestamp", System.currentTimeMillis());
            session.sendAsync(message);
        }
    }
}
