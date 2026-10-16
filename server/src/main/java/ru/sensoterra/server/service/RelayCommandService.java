package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.CommandLog;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.CommandLogRepository;
import ru.sensoterra.server.repository.RelayRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Optional;

@Singleton
public class RelayCommandService {

    private final MqttClientService mqttClientService;
    private final RelayRepository relayRepository;
    private final CommandLogRepository commandLogRepository;

    public RelayCommandService(MqttClientService mqttClientService,
                               RelayRepository relayRepository,
                               CommandLogRepository commandLogRepository) {
        this.mqttClientService = mqttClientService;
        this.relayRepository = relayRepository;
        this.commandLogRepository = commandLogRepository;
    }

    public CommandResult send(Long relayId, int state) {
        if (state != 0 && state != 1) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "state must be 0 or 1");
        }
        Relay relay = relayRepository.findById(relayId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Relay not found"));
        if (Boolean.FALSE.equals(relay.getActivity())) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Relay is not active");
        }
        if (!mqttClientService.isConnected()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT broker not connected");
        }

        boolean sent = mqttClientService.publishRelayCommand(
                relay.getDeviceId(), relay.getRelayNumber(), state);

        if (!sent) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to publish relay command");
        }

        logCommand(relay, state);
        relay.setState((short) state);
        relay.setUpdatedAt(Instant.now());
        relayRepository.update(relay);

        return new CommandResult(relay.getDeviceId(), relay.getRelayNumber(), state, true);
    }

    private void logCommand(Relay relay, int state) {
        CommandLog commandLog = new CommandLog();
        commandLog.setDeviceId(relay.getDeviceId());
        commandLog.setCommandType("RELAY_CONTROL");
        commandLog.setPayload(relay.getRelayNumber() + ";" + state);
        commandLog.setTopic(relay.getDeviceId() + "/CONTROL_R");
        commandLog.setSentAt(Instant.now());
        commandLog.setConfirmed(false);
        commandLogRepository.save(commandLog);
    }

    @Serdeable
    public record CommandResult(Long deviceId, Short relayNumber, Integer state, Boolean sent) {
    }
}