package ru.sensoterra.server.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import ru.sensoterra.server.entity.AdminAuditEntry;
import ru.sensoterra.server.service.AdminAuditService;
import ru.sensoterra.server.service.DeviceAccessService;

import java.util.List;

/** Admin sign-ins and actions, newest first; page back with {@code before} = last id seen. */
@Controller("/api/admin/audit")
@Secured("ADMIN")
public class AdminAuditController {

    private final AdminAuditService audit;
    private final DeviceAccessService access;

    public AdminAuditController(AdminAuditService audit, DeviceAccessService access) {
        this.audit = audit;
        this.access = access;
    }

    @Get
    public List<AdminAuditEntry> list(@QueryValue(defaultValue = "50") int limit,
                                      @Nullable @QueryValue Long before) {
        access.requireAdmin();
        return audit.page(before, limit);
    }
}
