package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;

@Entity
@Table(name = "sensor_scale",
       uniqueConstraints = @UniqueConstraint(columnNames = {"sensor_id", "field_key"}))
public class SensorScale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sensor_scale_sensor"))
    private Sensor sensor;

    @NotBlank
    @Pattern(regexp = "^v[123]$")
    @Column(name = "field_key", nullable = false, length = 3)
    private String fieldKey;

    @NotNull
    @Column(name = "min_value", nullable = false)
    private Integer minValue;

    @NotNull
    @Column(name = "max_value", nullable = false)
    private Integer maxValue;

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be hex #RRGGBB")
    @Column(name = "min_color", nullable = false, length = 7)
    private String minColor;

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be hex #RRGGBB")
    @Column(name = "max_color", nullable = false, length = 7)
    private String maxColor;

    @NotNull
    @Column(nullable = false)
    private Boolean enabled = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }

    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }

    public Integer getMinValue() { return minValue; }
    public void setMinValue(Integer minValue) { this.minValue = minValue; }

    public Integer getMaxValue() { return maxValue; }
    public void setMaxValue(Integer maxValue) { this.maxValue = maxValue; }

    public String getMinColor() { return minColor; }
    public void setMinColor(String minColor) { this.minColor = minColor; }

    public String getMaxColor() { return maxColor; }
    public void setMaxColor(String maxColor) { this.maxColor = maxColor; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
