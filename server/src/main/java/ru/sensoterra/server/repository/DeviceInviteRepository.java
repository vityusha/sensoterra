package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.DeviceInvite;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceInviteRepository extends JpaRepository<DeviceInvite, Long> {

    Optional<DeviceInvite> findByToken(String token);

    List<DeviceInvite> findByChipIdOrderByCreatedAtDesc(Long chipId);

    List<DeviceInvite> findByChipIdAndRevokedFalse(Long chipId);

    Optional<DeviceInvite> findByIdAndChipId(Long id, Long chipId);
}