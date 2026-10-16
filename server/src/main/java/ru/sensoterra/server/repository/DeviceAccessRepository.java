package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.DeviceAccess;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;

@Repository
public interface DeviceAccessRepository extends JpaRepository<DeviceAccess, Long> {

    List<DeviceAccess> findByUserId(Long userId);

    List<DeviceAccess> findByChipId(Long chipId);

    void deleteByUserIdAndChipId(Long userId, Long chipId);

    void deleteByUserId(Long userId);

    void deleteByChipId(Long chipId);
}
