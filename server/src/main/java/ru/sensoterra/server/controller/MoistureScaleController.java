package ru.sensoterra.server.controller;

import ru.sensoterra.server.dto.MoistureScaleDto;
import ru.sensoterra.server.entity.MoistureScale;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.MoistureScaleService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.constraints.*;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller("/api/sensors")
public class MoistureScaleController {
    private final MoistureScaleService service;
    private final DeviceAccessService access;

    public MoistureScaleController(MoistureScaleService service, DeviceAccessService access) {
        this.service = service;
        this.access = access;
    }

    @io.micronaut.serde.annotation.Serdeable
    public record SetRequest(
            @NotNull @Min(0) @Max(100) Integer minPct,
            @NotNull @Min(0) @Max(100) Integer maxPct,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String minColor,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String maxColor,
            @NotNull Boolean enabled
    ) {}

    @Get("/{chipId}/moisture-scale")
    public MoistureScaleDto get(@PathVariable Long chipId) {
        MoistureScale ms = service.findBySensorChipId(chipId).orElse(null);
        if (ms == null) {
            return new MoistureScaleDto(null, String.valueOf(chipId), 0, 100, "#ff0000", "#0000ff", false);
        }
        return toDto(ms, chipId);
    }

    @Put("/{chipId}/moisture-scale")
    public MoistureScaleDto set(@PathVariable Long chipId, @Body SetRequest req) {
        access.requireSensorControlByChip(chipId);
        MoistureScale ms = service.saveByChipId(chipId, req.minPct(), req.maxPct(), req.minColor(), req.maxColor(), req.enabled());
        return toDto(ms, chipId);
    }

    @Delete("/{chipId}/moisture-scale")
    @Status(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable Long chipId) {
        access.requireSensorControlByChip(chipId);
        service.deleteByChipId(chipId);
    }

    private MoistureScaleDto toDto(MoistureScale ms, Long chipId) {
        return new MoistureScaleDto(
                ms.getSensor() != null ? ms.getSensor().getId() : null,
                String.valueOf(chipId),
                ms.getMinPct(),
                ms.getMaxPct(),
                ms.getMinColor(),
                ms.getMaxColor(),
                ms.getEnabled()
        );
    }
}
