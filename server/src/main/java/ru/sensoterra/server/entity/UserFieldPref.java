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
 * Подпись и единица измерения канала (v0..v3) для типа датчика. Принадлежит
 * пользователю: одинаковый смысл канала у всех датчиков типа, но называть его можно
 * по-разному — меряющий влажность воздуха в доме не обязан называть v0 «влажностью
 * почвы».
 */
@Entity
@Table(name = "user_field_pref")
@IdClass(UserFieldPref.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class UserFieldPref {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "sensor_type", nullable = false)
    private Short sensorType;

    @Id
    @Column(name = "field", nullable = false, length = 8)
    private String field;

    /** Название канала; null — показывать как есть (v0). */
    @Column(name = "label", length = 100)
    private String label;

    /** Единица измерения, например «°C»; null или пусто — без единицы. */
    @Column(name = "unit", length = 20)
    private String unit;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Introspected // тот же ключ, тот же контракт findById
    public static class Key implements Serializable {
        private Long userId;
        private Short sensorType;
        private String field;
    }
}
