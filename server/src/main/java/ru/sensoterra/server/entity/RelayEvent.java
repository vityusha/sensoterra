package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Serdeable
@Table(name = "relay_event")
@IdClass(RelayEvent.PK.class)
@Data
public class RelayEvent {

    @Id
    private Instant time;

    @Id @Column(name = "device_id")
    private Long deviceId;

    @Id @Column(name = "relay_number")
    private Short relayNumber;

    private Short state;

    private Boolean fault;

    @Column(name = "rssi_r")
    private Integer rssiR;

    private Float vbat;

    private Float vacc;

    public static class PK implements Serializable {
        private Instant time;
        private Long deviceId;
        private Short relayNumber;

        public PK() {}

        public PK(Instant time, Long deviceId, Short relayNumber) {
            this.time = time;
            this.deviceId = deviceId;
            this.relayNumber = relayNumber;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(time, pk.time)
                && Objects.equals(deviceId, pk.deviceId)
                && Objects.equals(relayNumber, pk.relayNumber);
        }

        @Override public int hashCode() {
            return Objects.hash(time, deviceId, relayNumber);
        }
    }
}
