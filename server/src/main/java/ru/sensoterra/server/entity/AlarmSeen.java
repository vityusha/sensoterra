package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** When the user last opened the alarms page; later starts/ends count as new for the bell. */
@Entity
@Table(name = "alarm_seen")
@Data
public class AlarmSeen {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;
}
