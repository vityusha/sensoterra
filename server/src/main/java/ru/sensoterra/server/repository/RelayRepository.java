package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.Relay;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelayRepository extends JpaRepository<Relay, Long> {

    Optional<Relay> findByDeviceIdAndRelayNumber(Long deviceId, Short relayNumber);

    List<Relay> findByDeviceIdOrderByRelayNumberAsc(Long deviceId);
}