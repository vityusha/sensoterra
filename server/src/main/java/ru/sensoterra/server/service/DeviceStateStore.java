package ru.sensoterra.server.service;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DeviceStateStore {

    private final Map<Long, String> statuses = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();
    private volatile String lastStatus = "unknown";

    public void setStatus(Long deviceId, String status) {
        statuses.put(deviceId, status);
        lastSeen.put(deviceId, Instant.now());
        lastStatus = status;
    }

    /**
     * Восстановление статусов после рестарта сервера из device.last_status.
     * Статус каждого устройства определяется исключительно последним STATUS-топиком
     * (online при connect, offline при disconnect). Блоки с persistent MQTT-сессией
     * не переподключаются после рестарта и не публикуют STATUS повторно, поэтому без
     * восстановления они "залипают" оффлайн до реального события.
     */
    public void seedStatuses(java.util.Map<Long, String> lastStatuses) {
        if (lastStatuses == null) {
            return;
        }
        for (java.util.Map.Entry<Long, String> e : lastStatuses.entrySet()) {
            statuses.put(e.getKey(), e.getValue());
        }
    }

    public void touch(Long deviceId) {
        lastSeen.put(deviceId, Instant.now());
    }

    public void remove(Long deviceId) {
        statuses.remove(deviceId);
        lastSeen.remove(deviceId);
    }

    public String getStatus(Long deviceId) {
        return statuses.getOrDefault(deviceId, "unknown");
    }

    public Instant getLastSeen(Long deviceId) {
        return lastSeen.get(deviceId);
    }

    public String getLastStatus() {
        return lastStatus;
    }
}