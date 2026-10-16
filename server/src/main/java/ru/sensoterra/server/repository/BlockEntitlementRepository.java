package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.BlockEntitlement;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockEntitlementRepository extends JpaRepository<BlockEntitlement, Long> {

    Optional<BlockEntitlement> findByChipId(Long chipId);

    List<BlockEntitlement> findByStartedByUserId(Long userId);
}
