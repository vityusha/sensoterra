package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.Sensor;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    Optional<Sensor> findByDeviceIdAndSensorChipId(Long deviceId, Long sensorChipId);

    Optional<Sensor> findBySensorChipId(Long sensorChipId);

    List<Sensor> findBySensorChipIdOrderByUpdatedAtDesc(Long sensorChipId);

    List<Sensor> findBySensorChipIdOrderByLastTimeDesc(Long sensorChipId);

    List<Sensor> findBySensorChipIdAndLastTimeIsNotNullOrderByLastTimeDesc(Long sensorChipId);

    @Query("SELECT DISTINCT s.sensorType FROM Sensor s WHERE s.sensorType IS NOT NULL ORDER BY s.sensorType")
    List<Short> findDistinctSensorTypes();

    List<Sensor> findByDeviceId(Long deviceId);
}