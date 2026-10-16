package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.Device;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByChipId(Long chipId);

    List<Device> findByDeviceType(String deviceType);
}