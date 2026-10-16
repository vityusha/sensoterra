package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.MoistureScale;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.Optional;

@Repository
public interface MoistureScaleRepository extends CrudRepository<MoistureScale, Long> {
    Optional<MoistureScale> findBySensorId(Long sensorId);
}
