package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** Admin sign-in or admin action (see AdminAuditService). */
@Entity
@Serdeable
@Table(name = "admin_audit")
@Data
public class AdminAuditEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 50)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 32)
    private String event;

    @Column(length = 64)
    private String ip;

    @Column(length = 255)
    private String detail;
}
