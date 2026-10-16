package ru.sensoterra.server.controller;

import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.RelayCommandService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Status;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;

@Controller("/api/relays")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class RelayCommandController {

    private final RelayCommandService relayCommandService;
    private final DeviceAccessService access;

    public RelayCommandController(RelayCommandService relayCommandService, DeviceAccessService access) {
        this.relayCommandService = relayCommandService;
        this.access = access;
    }

    @Post("/{relayId}/command")
    @Status(HttpStatus.OK)
    @Produces(MediaType.APPLICATION_JSON)
    public RelayCommandService.CommandResult command(Long relayId, @Body RelayCommandRequest request) {
        access.requireRelayControl(relayId);
        return relayCommandService.send(relayId, request.state);
    }

    @Serdeable
    public static class RelayCommandRequest {
        private int state;

        public int getState() {
            return state;
        }

        public void setState(int state) {
            this.state = state;
        }
    }
}