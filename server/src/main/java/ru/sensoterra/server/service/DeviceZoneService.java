package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.repository.DeviceRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит часовой пояс устройства (смещение от UTC в минутах, локальное = UTC + offset).
 * Дефолт 180 = Москва: автономные датчики в прошивке жёстко в Москве, у блоков зона
 * становится известна из поля Zone в STATE_R (прошивка передаёт минуты, которые надо
 * вычесть из UTC, чтобы получить локальное: local = UTC - zone, значит offset = -zone).
 */
@Singleton
public class DeviceZoneService {

    public static final int DEFAULT_OFFSET_MINUTES = 180;

    private final DeviceRepository deviceRepository;
    private final Map<Long, Integer> cache = new ConcurrentHashMap<>();

    public DeviceZoneService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public ZoneId zoneFor(Long deviceId) {
        return ZoneOffset.ofTotalSeconds(offsetFor(deviceId) * 60);
    }

    public int offsetFor(Long deviceId) {
        Integer cached = cache.get(deviceId);
        if (cached != null) {
            return cached;
        }
        int offset = deviceRepository.findByChipId(deviceId)
                .map(Device::getTzOffsetMinutes)
                .filter(v -> v != null)
                .orElse(DEFAULT_OFFSET_MINUTES);
        cache.put(deviceId, offset);
        return offset;
    }

    /**
     * Сохраняет зону устройства из прошивки. zoneMinutes — прошивочный Zone
     * (local = UTC - zone), поэтому в БД пишем инвертированное значение.
     */
    @Transactional
    public void updateZone(Long deviceId, int zoneMinutes) {
        int offset = -zoneMinutes;
        Device device = deviceRepository.findByChipId(deviceId)
                .orElseGet(() -> {
                    Device d = new Device();
                    d.setChipId(deviceId);
                    d.setDeviceType("BLOCK");
                    d.setCreatedAt(java.time.Instant.now());
                    return d;
                });
        device.setTzOffsetMinutes(offset);
        deviceRepository.save(device);
        cache.put(deviceId, offset);
    }

    /**
     * Убирает устройство из кэша зон (при удалении устройства).
     */
    public void remove(Long deviceId) {
        cache.remove(deviceId);
    }
}