package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Личные настройки отображения конкретного датчика: какие его каналы скрыты и в каких
 * пределах рисовать ось Y. Привязка к датчику, а не к типу: у разных датчиков одного
 * типа аппаратно разный набор каналов, и скрывать лишние надо независимо.
 */
@Entity
@Table(name = "user_sensor_pref")
@IdClass(UserSensorPref.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class UserSensorPref {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Чип датчика: у автономного он равен chip_id устройства, у встроенного — своему. */
    @Id
    @Column(name = "sensor_chip_id", nullable = false)
    private Long sensorChipId;

    /** Скрытые каналы через запятую: "v1,v2". Пусто — показывать всё. */
    @Column(name = "hidden_fields", nullable = false, length = 8)
    private String hiddenFields = "";

    /** Границы оси Y по каналам в виде JSON: {"v0":{"min":0,"max":100}}. */
    @Column(name = "y_range")
    private String yRange;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private Long sensorChipId;
    }
}
