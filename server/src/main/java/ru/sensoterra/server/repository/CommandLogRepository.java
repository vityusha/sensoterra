package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.CommandLog;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.Sort;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {

    Optional<CommandLog> findFirstByDeviceIdAndCommandTypeAndConfirmedFalseOrderBySentAtDesc(
            Long deviceId, String commandType);

    List<CommandLog> findByDeviceIdAndCommandType(Long deviceId, String commandType);

    default List<CommandLog> findLatest() {
        return findAll(Sort.of(Sort.Order.desc("sentAt")));
    }
}