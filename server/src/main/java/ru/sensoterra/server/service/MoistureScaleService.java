package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.MoistureScale;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.MoistureScaleRepository;
import ru.sensoterra.server.repository.SensorRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.Optional;

@Singleton
public class MoistureScaleService {
    private final MoistureScaleRepository repo;
    private final SensorRepository sensorRepo;

    public MoistureScaleService(MoistureScaleRepository repo, SensorRepository sensorRepo) {
        this.repo = repo;
        this.sensorRepo = sensorRepo;
    }

    @Transactional
    public MoistureScale getOrCreate(Long sensorId) {
        return repo.findBySensorId(sensorId).orElseGet(() -> {
            MoistureScale ms = new MoistureScale();
            ms.setSensor(sensorRepo.findById(sensorId).orElseThrow(() -> new IllegalArgumentException("sensor not found")));
            ms.setMinPct(0);
            ms.setMaxPct(100);
            ms.setMinColor("#ff0000");
            ms.setMaxColor("#0000ff");
            ms.setEnabled(false);
            return repo.save(ms);
        });
    }

    public Optional<MoistureScale> findBySensorId(Long sensorId) {
        return repo.findBySensorId(sensorId);
    }

    public Optional<MoistureScale> findBySensorChipId(Long chipId) {
        return sensorRepo.findBySensorChipId(chipId).flatMap(s -> repo.findBySensorId(s.getId()));
    }

    @Transactional
    public MoistureScale saveByChipId(Long chipId, Integer minPct, Integer maxPct, String minColor, String maxColor, Boolean enabled) {
        Sensor s = sensorRepo.findBySensorChipId(chipId)
                .orElseThrow(() -> new io.micronaut.http.exceptions.HttpStatusException(
                        io.micronaut.http.HttpStatus.NOT_FOUND, "sensor not found"));
        MoistureScale ms = repo.findBySensorId(s.getId()).orElse(new MoistureScale());
        ms.setSensor(s);
        ms.setMinPct(minPct);
        ms.setMaxPct(maxPct);
        ms.setMinColor(minColor);
        ms.setMaxColor(maxColor);
        ms.setEnabled(enabled != null && enabled);
        validate(ms);
        return repo.save(ms);
    }

    @Transactional
    public void deleteByChipId(Long chipId) {
        sensorRepo.findBySensorChipId(chipId).ifPresent(s -> repo.findBySensorId(s.getId()).ifPresent(repo::delete));
    }

    private void validate(MoistureScale ms) {
        if (ms.getMinPct() == null || ms.getMaxPct() == null) throw new IllegalArgumentException("pct required");
        if (ms.getMinPct() < 0 || ms.getMinPct() > 100 || ms.getMaxPct() < 0 || ms.getMaxPct() > 100)
            throw new IllegalArgumentException("pct out of range");
        if (ms.getMinPct() >= ms.getMaxPct()) throw new IllegalArgumentException("min >= max");
        if (ms.getMinColor() == null || !ms.getMinColor().matches("^#[0-9A-Fa-f]{6}$"))
            throw new IllegalArgumentException("bad min color");
        if (ms.getMaxColor() == null || !ms.getMaxColor().matches("^#[0-9A-Fa-f]{6}$"))
            throw new IllegalArgumentException("bad max color");
    }
}
