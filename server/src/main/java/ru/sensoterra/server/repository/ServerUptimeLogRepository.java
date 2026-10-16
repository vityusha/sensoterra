package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.ServerUptimeLog;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerUptimeLogRepository extends JpaRepository<ServerUptimeLog, Long> {

    Optional<ServerUptimeLog> findFirstByStoppedAtIsNullOrderByStartedAtDesc();

    List<ServerUptimeLog> findByStartedAtIsNotNullOrderByStartedAtDesc();
}
