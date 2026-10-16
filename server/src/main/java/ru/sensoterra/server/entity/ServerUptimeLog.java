package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Serdeable
@Table(name = "server_uptime_log")
@Data
public class ServerUptimeLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "last_seen")
    private Instant lastSeen;
}
