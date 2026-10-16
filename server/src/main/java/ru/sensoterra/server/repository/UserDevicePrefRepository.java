package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.UserDevicePref;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDevicePrefRepository extends JpaRepository<UserDevicePref, UserDevicePref.Key> {

    List<UserDevicePref> findByUserId(Long userId);

    Optional<UserDevicePref> findByUserIdAndChipId(Long userId, Long chipId);
}
