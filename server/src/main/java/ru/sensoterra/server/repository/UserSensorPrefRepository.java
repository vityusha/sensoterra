package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.UserSensorPref;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSensorPrefRepository extends JpaRepository<UserSensorPref, UserSensorPref.Key> {

    List<UserSensorPref> findByUserId(Long userId);

    Optional<UserSensorPref> findByUserIdAndSensorChipId(Long userId, Long sensorChipId);
}
