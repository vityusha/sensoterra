package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.AdminRecoveryCode;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.List;

@Repository
public interface AdminRecoveryCodeRepository extends JpaRepository<AdminRecoveryCode, Long> {

    List<AdminRecoveryCode> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
