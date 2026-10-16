package ru.sensoterra.server.controller;

import ru.sensoterra.server.dto.SensorScaleDto;
import ru.sensoterra.server.entity.SensorScale;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.SensorScaleService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.constraints.*;

import java.util.List;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller("/api/sensors")
public class SensorScaleController {
    private final SensorScaleService service;
    private final DeviceAccessService access;

    public SensorScaleController(SensorScaleService service, DeviceAccessService access) {
        this.service = service;
        this.access = access;
    }

    @io.micronaut.serde.annotation.Serdeable
    public record SetRequest(
            @NotBlank @Pattern(regexp = "^v[123]$") String fieldKey,
            @NotNull Integer minValue,
            @NotNull Integer maxValue,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String minColor,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String maxColor,
            @NotNull Boolean enabled
    ) {}

    @Get("/{chipId}/scale/{fieldKey}")
    public SensorScaleDto get(@PathVariable Long chipId, @PathVariable String fieldKey) {
        SensorScale sc = service.getOrDefault(chipId, fieldKey);
        return toDto(sc, chipId);
    }

    @Get("/{chipId}/scale")
    public List<SensorScaleDto> getAll(@PathVariable Long chipId) {
        // Всегда отдаём v1/v2/v3: сохранённую шкалу либо дефолт, если её нет.
        return List.of("v1", "v2", "v3").stream()
                .map(fk -> toDto(service.getOrDefault(chipId, fk), chipId))
                .toList();
    }

    @Put("/{chipId}/scale")
    public SensorScaleDto set(@PathVariable Long chipId, @Body SetRequest req) {
        access.requireSensorControlByChip(chipId);
        SensorScale sc = service.saveByChipId(chipId, req.fieldKey(), req.minValue(), req.maxValue(),
                req.minColor(), req.maxColor(), req.enabled());
        return toDto(sc, chipId);
    }

    @Delete("/{chipId}/scale/{fieldKey}")
    @Status(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable Long chipId, @PathVariable String fieldKey) {
        access.requireSensorControlByChip(chipId);
        service.deleteByChipId(chipId, fieldKey);
    }

    private SensorScaleDto toDto(SensorScale sc, Long chipId) {
        return new SensorScaleDto(
                sc.getSensor() != null ? sc.getSensor().getId() : null,
                String.valueOf(chipId),
                sc.getFieldKey(),
                sc.getMinValue(),
                sc.getMaxValue(),
                sc.getMinColor(),
                sc.getMaxColor(),
                sc.getEnabled()
        );
    }
}
