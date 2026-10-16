package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.UserFieldPref;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFieldPrefRepository extends JpaRepository<UserFieldPref, UserFieldPref.Key> {

    List<UserFieldPref> findByUserId(Long userId);

    Optional<UserFieldPref> findByUserIdAndSensorTypeAndField(Long userId, Short sensorType, String field);

    List<UserFieldPref> findBySensorTypeAndField(Short sensorType, String field);

    List<UserFieldPref> findBySensorType(Short sensorType);
}
