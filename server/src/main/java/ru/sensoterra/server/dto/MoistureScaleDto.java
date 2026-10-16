package ru.sensoterra.server.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MoistureScaleDto(
        Long sensorId,
        String chipId,
        Integer minPct,
        Integer maxPct,
        String minColor,
        String maxColor,
        Boolean enabled
) {}
