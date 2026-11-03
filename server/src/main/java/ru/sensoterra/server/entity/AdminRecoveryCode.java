package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One-time recovery code of an admin's 2FA; only its SHA-256 is stored. */
@Entity
@Table(name = "admin_recovery_code")
@Data
public class AdminRecoveryCode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;
}
