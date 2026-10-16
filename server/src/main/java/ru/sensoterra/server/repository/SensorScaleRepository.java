package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.SensorScale;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SensorScaleRepository extends CrudRepository<SensorScale, Long> {
    List<SensorScale> findBySensorId(Long sensorId);
    Optional<SensorScale> findBySensorIdAndFieldKey(Long sensorId, String fieldKey);
}
