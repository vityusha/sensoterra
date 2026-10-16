package ru.sensoterra.server.entity;

import io.micronaut.core.annotation.Introspected;
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
 * Подпись и единица канала, которые видит пользователь, пока не завёл свои: значение
 * по умолчанию для типа датчика. В отличие от {@link UserFieldPref}, строка одна на
 * всех, принадлежит админу и правится из интерфейса, а не правкой кода.
 */
@Entity
@Table(name = "sensor_field_default")
@IdClass(SensorFieldDefault.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class SensorFieldDefault {

    @Id
    @Column(name = "sensor_type", nullable = false)
    private Short sensorType;

    @Id
    @Column(name = "field", nullable = false, length = 8)
    private String field;

    /** Название канала; null — показывать сырой ключ (v0). */
    @Column(name = "label", length = 100)
    private String label;

    /** Единица измерения, например «°C»; null или пусто — без единицы. */
    @Column(name = "unit", length = 20)
    private String unit;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Introspected // без него findById(Key) падает в IntrospectionException
    public static class Key implements Serializable {
        private Short sensorType;
        private String field;
    }
}