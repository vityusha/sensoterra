package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.SensorCalibrationDefault;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.Optional;

@Repository
public interface SensorCalibrationDefaultRepository
        extends JpaRepository<SensorCalibrationDefault, Short> {

    Optional<SensorCalibrationDefault> findBySensorType(Short sensorType);
}