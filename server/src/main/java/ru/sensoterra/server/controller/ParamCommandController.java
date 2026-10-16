package ru.sensoterra.server.controller;

import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.ParamCommandService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

@Controller("/api")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class ParamCommandController {

    private final ParamCommandService paramCommandService;
    private final DeviceAccessService access;

    public ParamCommandController(ParamCommandService paramCommandService, DeviceAccessService access) {
        this.paramCommandService = paramCommandService;
        this.access = access;
    }

    @Post("/relays/{relayId}/params")
    @Status(HttpStatus.OK)
    public ParamCommandService.CommandResult relayParams(Long relayId,
                                                         @Body ParamCommandService.RelayParamsRequest request) {
        access.requireRelayControl(relayId);
        return paramCommandService.relayParams(relayId, request);
    }

    @Post("/sensors/{sensorId}/params")
    @Status(HttpStatus.OK)
    public ParamCommandService.CommandResult sensorParams(Long sensorId,
                                                          @Body ParamCommandService.SensorParamsRequest request) {
        access.requireSensorControl(sensorId);
        return paramCommandService.sensorParams(sensorId, request);
    }
}