package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Настройка сервера, которую админ меняет из интерфейса, а не правкой базы.
 * Значение хранится строкой: так в одной таблице живут и числа, и флаги.
 */
@Entity
@Serdeable
@Table(name = "server_setting")
@Data
public class ServerSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    // Имя колонки не «value»: это зарезервированное слово в H2, тестовая схема падала бы.
    @Column(name = "setting_value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
