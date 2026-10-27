package ru.sensoterra.server.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DeviceStateStore {

    private static final Logger log = LoggerFactory.getLogger(DeviceStateStore.class);

    /**
     * Upper bound of devices tracked in memory. Any MQTT publisher can invent device ids
     * (topic {@code <any number>/PING}); without a cap the maps grow until the JVM runs out
     * of heap. Far above any real fleet; new ids past it are simply not tracked.
     */
    static final int MAX_TRACKED_DEVICES = 10_000;

    private final Map<Long, String> statuses = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();
    private volatile boolean capWarned;

    public void setStatus(Long deviceId, String status) {
        if (!canTrack(deviceId)) {
            return;
        }
        statuses.put(deviceId, status);
        lastSeen.put(deviceId, Instant.now());
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
        if (canTrack(deviceId)) {
            lastSeen.put(deviceId, Instant.now());
        }
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

    private boolean canTrack(Long deviceId) {
        if (lastSeen.containsKey(deviceId) || statuses.containsKey(deviceId)
                || Math.max(lastSeen.size(), statuses.size()) < MAX_TRACKED_DEVICES) {
            return true;
        }
        if (!capWarned) {
            capWarned = true;
            log.warn("Device state store is full ({} devices): new device ids are not tracked", MAX_TRACKED_DEVICES);
        }
        return false;
    }
}
