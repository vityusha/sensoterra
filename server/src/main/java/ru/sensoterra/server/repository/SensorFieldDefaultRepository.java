package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.SensorFieldDefault;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;

@Repository
public interface SensorFieldDefaultRepository extends JpaRepository<SensorFieldDefault, SensorFieldDefault.Key> {

    List<SensorFieldDefault> findBySensorTypeOrderByField(Short sensorType);
}