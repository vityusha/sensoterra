package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.AdminAuditEntry;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.Pageable;

import java.util.List;

@Repository
public interface AdminAuditRepository extends JpaRepository<AdminAuditEntry, Long> {

    /** Newest first; {@code beforeId} pages back (Long.MAX_VALUE for the first page). */
    List<AdminAuditEntry> findByIdLessThanOrderByIdDesc(Long beforeId, Pageable pageable);
}
