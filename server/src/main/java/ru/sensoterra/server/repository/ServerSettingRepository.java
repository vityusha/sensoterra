package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.ServerSetting;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.Optional;

@Repository
public interface ServerSettingRepository extends JpaRepository<ServerSetting, String> {

    Optional<ServerSetting> findByKey(String key);
}
