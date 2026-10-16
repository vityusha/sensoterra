package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.SensorRegistration;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorRegistrationRepository extends JpaRepository<SensorRegistration, Long> {

    Optional<SensorRegistration> findByDeviceIdAndSlot(Long deviceId, Short slot);

    List<SensorRegistration> findByDeviceId(Long deviceId);

    List<SensorRegistration> findByDeviceIdAndSensorChipIdIsNotNull(Long deviceId);

    List<SensorRegistration> findBySensorChipId(Long sensorChipId);
}