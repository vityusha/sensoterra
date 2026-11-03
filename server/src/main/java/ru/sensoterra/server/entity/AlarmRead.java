package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** A finished alarm the user has clicked: no longer shown in bold for that user. */
@Entity
@Table(name = "alarm_read")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlarmRead {

    @EmbeddedId
    private Key id;

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {

        @Column(name = "alarm_id", nullable = false)
        private Long alarmId;

        @Column(name = "user_id", nullable = false)
        private Long userId;
    }
}
