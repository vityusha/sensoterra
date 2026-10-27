package ru.sensoterra.server.ws;

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

    /**
     * Рассылает событие только тем сессиям, которым доступно устройство из сообщения.
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

    /**
     * Устройство сообщения — это deviceId. Для данных датчика deviceId — блок-родитель,
     * а chipId лишь уточняет датчик. Доступ выдан на устройство (блок), отдельной записи
     * доступа для датчика в слоте нет, поэтому проверять надо именно deviceId: иначе
     * пользователь с доступом к блоку не получал бы live-обновления его датчиков.
     */
    private Set<Long> chipsOf(Map<String, Object> message) {
        Set<Long> chips = new HashSet<>();
        if (addChip(chips, message.get("deviceId"))) {
            return chips;
        }
        addChip(chips, message.get("chipId"));
        return chips;
    }

    private boolean addChip(Set<Long> chips, Object value) {
        if (value instanceof Number n) {
            chips.add(n.longValue());
            return true;
        }
        return false;
    }

}
