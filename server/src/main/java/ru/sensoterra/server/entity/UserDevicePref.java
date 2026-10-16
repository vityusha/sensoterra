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
 * Личные настройки отображения одного устройства (блока или автономного датчика).
 * Принадлежат пользователю, а не устройству: у каждого свой алиас имени и своё
 * решение поVacc. Ключ составной — (пользователь, устройство).
 */
@Entity
@Table(name = "user_device_pref")
@IdClass(UserDevicePref.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class UserDevicePref {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "chip_id", nullable = false)
    private Long chipId;

    /**
     * Vacc скрыт по умолчанию: у большинства блоков питание от сети и колонка пустая.
     * Блоки на аккумуляторе пользователь включает обратно.
     */
    @Column(name = "hide_vacc", nullable = false)
    private boolean hideVacc = true;

    /** Алиас имени для этого пользователя; null — показывать общее device.name. */
    @Column(name = "name", length = 100)
    private String name;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private Long chipId;
    }
}
