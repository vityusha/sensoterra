package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.SensorCalibration;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.Optional;

@Repository
public interface SensorCalibrationRepository extends JpaRepository<SensorCalibration, Long> {

    Optional<SensorCalibration> findBySensorId(Long sensorId);

    boolean existsBySensorId(Long sensorId);
}