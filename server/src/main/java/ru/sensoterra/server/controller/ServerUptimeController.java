package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.ServerUptimeLog;
import ru.sensoterra.server.repository.ServerUptimeLogRepository;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@Controller("/api/server/uptime")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class ServerUptimeController {

    private final ServerUptimeLogRepository repository;

    public ServerUptimeController(ServerUptimeLogRepository repository) {
        this.repository = repository;
    }

    @Get("/current")
    @Transactional
    public Optional<ServerUptimeLog> current() {
        return repository.findFirstByStoppedAtIsNullOrderByStartedAtDesc();
    }

    @Get
    @Transactional
    public List<ServerUptimeLog> history() {
        return repository.findByStartedAtIsNotNullOrderByStartedAtDesc();
    }
}
