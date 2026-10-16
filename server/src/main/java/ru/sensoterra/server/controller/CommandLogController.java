package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.CommandLog;
import ru.sensoterra.server.repository.CommandLogRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

import java.util.List;
import java.util.Set;

@Controller("/api/commands")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class CommandLogController {

    private final CommandLogRepository commandLogRepository;
    private final DeviceAccessService access;

    public CommandLogController(CommandLogRepository commandLogRepository, DeviceAccessService access) {
        this.commandLogRepository = commandLogRepository;
        this.access = access;
    }

    @Get
    public List<CommandLog> list(@QueryValue(defaultValue = "50") int limit,
                                 @QueryValue(defaultValue = "0") int offset) {
        Set<Long> visible = access.visibleChipIds();
        List<CommandLog> commands = commandLogRepository.findLatest().stream()
                .filter(c -> c.getDeviceId() == null || visible.contains(c.getDeviceId()))
                .toList();
        int end = Math.min(Math.max(offset, 0) + limit, commands.size());
        int start = Math.min(Math.max(offset, 0), commands.size());
        return commands.subList(start, end);
    }
}