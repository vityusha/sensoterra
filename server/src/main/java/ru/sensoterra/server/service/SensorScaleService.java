package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorScale;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.repository.SensorScaleRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class SensorScaleService {
    private final SensorScaleRepository repo;
    private final SensorRepository sensorRepo;

    /** Дефолтные шкалы для полей v1/v2/v3 (используются, если нет сохранённой строки). */
    private static final Map<String, int[]> DEFAULTS = Map.of(
            // min, max — значения поля (int16/int16/uint16 из прошивки)
            "v1", new int[]{-50, 50},   // температура, °C
            "v2", new int[]{0, 100},    // влажность воздуха, %
            "v3", new int[]{0, 5000}    // освещенность, лк
    );
    private static final Map<String, String[]> DEFAULT_COLORS = Map.of(
            "v1", new String[]{"#0000ff", "#ff0000"}, // холодный → горячий
            "v2", new String[]{"#b0d0ff", "#0000ff"}, // сухо → влажно
            "v3", new String[]{"#000000", "#ffff00"}  // темно → ярко
    );

    public SensorScaleService(SensorScaleRepository repo, SensorRepository sensorRepo) {
        this.repo = repo;
        this.sensorRepo = sensorRepo;
    }

    /** Все шкалы датчика одним запросом (для страницы сенсора). */
    public List<SensorScale> findBySensorChipId(Long chipId) {
        return sensorRepo.findBySensorChipId(chipId)
                .map(s -> repo.findBySensorId(s.getId()))
                .orElse(List.of());
    }

    /** Шкала конкретного поля, с дефолтом, если не сохранена/выключена. */
    public SensorScale getOrDefault(Long chipId, String fieldKey) {
        return sensorRepo.findBySensorChipId(chipId)
                .flatMap(s -> repo.findBySensorIdAndFieldKey(s.getId(), fieldKey))
                .orElseGet(() -> defaultScale(fieldKey));
    }

    @Transactional
    public SensorScale saveByChipId(Long chipId, String fieldKey, Integer minValue, Integer maxValue,
                                    String minColor, String maxColor, Boolean enabled) {
        Sensor s = sensorRepo.findBySensorChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "sensor not found"));
        SensorScale sc = repo.findBySensorIdAndFieldKey(s.getId(), fieldKey).orElse(new SensorScale());
        sc.setSensor(s);
        sc.setFieldKey(fieldKey);
        sc.setMinValue(minValue);
        sc.setMaxValue(maxValue);
        sc.setMinColor(minColor);
        sc.setMaxColor(maxColor);
        sc.setEnabled(enabled != null && enabled);
        validate(sc);
        return repo.save(sc);
    }

    @Transactional
    public void deleteByChipId(Long chipId, String fieldKey) {
        sensorRepo.findBySensorChipId(chipId).ifPresent(s ->
                repo.findBySensorIdAndFieldKey(s.getId(), fieldKey).ifPresent(repo::delete));
    }

    private static SensorScale defaultScale(String fieldKey) {
        int[] r = DEFAULTS.getOrDefault(fieldKey, new int[]{0, 100});
        String[] c = DEFAULT_COLORS.getOrDefault(fieldKey, new String[]{"#ff0000", "#0000ff"});
        SensorScale sc = new SensorScale();
        sc.setFieldKey(fieldKey);
        sc.setMinValue(r[0]);
        sc.setMaxValue(r[1]);
        sc.setMinColor(c[0]);
        sc.setMaxColor(c[1]);
        // Дефолт неактивен: прямоугольник-градиент показывается только после
        // того, как пользователь сохранил шкалу для этого поля (как у влажности).
        sc.setEnabled(false);
        return sc;
    }

    private void validate(SensorScale sc) {
        if (sc.getFieldKey() == null || !sc.getFieldKey().matches("^v[123]$"))
            throw new IllegalArgumentException("bad field key");
        if (sc.getMinValue() == null || sc.getMaxValue() == null) throw new IllegalArgumentException("value required");
        if (sc.getMinValue() >= sc.getMaxValue()) throw new IllegalArgumentException("min >= max");
        if (sc.getMinColor() == null || !sc.getMinColor().matches("^#[0-9A-Fa-f]{6}$"))
            throw new IllegalArgumentException("bad min color");
        if (sc.getMaxColor() == null || !sc.getMaxColor().matches("^#[0-9A-Fa-f]{6}$"))
            throw new IllegalArgumentException("bad max color");
    }
}
