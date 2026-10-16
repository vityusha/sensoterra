package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;

@Entity
@Table(name = "moisture_scale")
public class MoistureScale {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id", nullable = false, unique = true, foreignKey = @ForeignKey(name = "fk_moisture_scale_sensor"))
    private Sensor sensor;

    @NotNull
    @Min(0)
    @Max(100)
    @Column(name = "min_pct", nullable = false)
    private Integer minPct;

    @NotNull
    @Min(0)
    @Max(100)
    @Column(name = "max_pct", nullable = false)
    private Integer maxPct;

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

    public Integer getMinPct() { return minPct; }
    public void setMinPct(Integer minPct) { this.minPct = minPct; }

    public Integer getMaxPct() { return maxPct; }
    public void setMaxPct(Integer maxPct) { this.maxPct = maxPct; }

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
