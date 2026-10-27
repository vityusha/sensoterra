package ru.sensoterra.server.controller;

import ru.sensoterra.server.mqtt.MqttClientService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

@Controller("/api")
public class DeviceStatusController {

    private final MqttClientService mqttService;

    public DeviceStatusController(MqttClientService mqttService) {
        this.mqttService = mqttService;
    }

    @Get("/status")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public StatusResponse getStatus() {
        // The server's MQTT link, not the raw STATUS of whichever device reported last:
        // that one went to every user, including devices they have no access to.
        return new StatusResponse(mqttService.isConnected() ? "online" : "offline");
    }

    @Serdeable
    public static class StatusResponse {
        private final String status;

        public StatusResponse(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }
    }
}
