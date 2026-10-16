package ru.sensoterra.server.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorScaleDto(
        Long sensorId,
        String chipId,
        String fieldKey,
        Integer minValue,
        Integer maxValue,
        String minColor,
        String maxColor,
        Boolean enabled
) {}
