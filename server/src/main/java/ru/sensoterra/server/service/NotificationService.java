package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.ws.DeviceStatusWebSocketBroadcaster;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long RE_NOTIFY_MINUTES = 15;
    private static final int MAX_REMEMBERED = 10_000;

    private final DeviceRepository deviceRepository;
    private final DeviceStatusWebSocketBroadcaster broadcaster;
    private final EntityManager entityManager;
    private final Map<String, Instant> lastNotified = new ConcurrentHashMap<>();

    public NotificationService(DeviceRepository deviceRepository,
                               DeviceStatusWebSocketBroadcaster broadcaster,
                               EntityManager entityManager) {
        this.deviceRepository = deviceRepository;
        this.broadcaster = broadcaster;
        this.entityManager = entityManager;
    }

    /**
     * Аномальное значение Vbat блока (<= 0, напр. -0.0) — признак зависшего
     * второго ESP блока: MQTT продолжает приходить, но Vbat не читается.
     * Vacc при этом у блоков на сетевом питании бывает -0.0 и неисправностью
     * не является, поэтому на него не смотрим.
     */
    @Scheduled(fixedDelay = "60s")
    @Transactional
    @SuppressWarnings("unchecked")
    public void checkBlockPower() {
        List<Object[]> rows;
        try {
            rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT ON (device_id) device_id, vbat " +
                    "FROM block_telemetry " +
                    "WHERE time >= now() - interval '5 minutes' " +
                    "ORDER BY device_id, time DESC")
                    .getResultList();
        } catch (Exception e) {
            log.warn("Block power check failed: {}", e.getMessage());
            return;
        }
        for (Object[] r : rows) {
            Long deviceId = r[0] != null ? ((Number) r[0]).longValue() : null;
            if (deviceId == null) {
                continue;
            }
            Float vbat = r[1] != null ? ((Number) r[1]).floatValue() : null;
            if (vbat == null || vbat > 0f) {
                continue;
            }
            String key = "power:" + deviceId;
            if (!canNotify(key)) {
                continue;
            }
            StringBuilder msg = new StringBuilder();
            msg.append("Блок ").append(blockLabel(deviceId));
            msg.append(": Vbat ").append(vbat).append(" В");
            msg.append(" — возможна неисправность блока (нет связи между контроллерами)");
            broadcast("invalidVoltage", deviceId, deviceId, msg.toString());
        }
    }

    private String blockLabel(Long deviceId) {
        try {
            Optional<Device> device = deviceRepository.findById(deviceId);
            String name = device.map(Device::getName).orElse(null);
            return name != null ? name + " (" + deviceId + ")" : String.valueOf(deviceId);
        } catch (Exception e) {
            return String.valueOf(deviceId);
        }
    }

    private boolean canNotify(String key) {
        if (lastNotified.size() > MAX_REMEMBERED) {
            // Entries older than the re-notify window no longer matter; drop them so the map
            // can't grow without bound.
            Instant cutoff = Instant.now().minusSeconds(RE_NOTIFY_MINUTES * 60);
            lastNotified.values().removeIf(t -> t.isBefore(cutoff));
        }
        Instant last = lastNotified.get(key);
        if (last == null || last.isBefore(Instant.now().minusSeconds(RE_NOTIFY_MINUTES * 60))) {
            lastNotified.put(key, Instant.now());
            return true;
        }
        return false;
    }

    private void broadcast(String type, Long deviceId, Long chipId, String message) {
        broadcaster.broadcast(Map.of(
                "type", "notify",
                "notifyType", type,
                "deviceId", deviceId,
                "chipId", chipId,
                "message", message,
                "timestamp", System.currentTimeMillis()));
        log.info("Notification {}: {}", type, message);
    }
}