package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.CommandLog;
import ru.sensoterra.server.service.DeviceAccessService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Set;

@Controller("/api/commands")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class CommandLogController {

    static final int MAX_LIMIT = 200;

    private final EntityManager em;
    private final DeviceAccessService access;

    public CommandLogController(EntityManager em, DeviceAccessService access) {
        this.em = em;
        this.access = access;
    }

    /**
     * Latest commands on the caller's devices. Filtering and paging happen in the database:
     * loading the whole command_log (up to a year) on every request was an easy DoS.
     */
    @Get
    @Transactional
    public List<CommandLog> list(@QueryValue(defaultValue = "50") int limit,
                                 @QueryValue(defaultValue = "0") int offset) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        int from = Math.max(offset, 0);
        boolean admin = access.isAdmin();
        Set<Long> visible = admin ? Set.of() : access.visibleChipIds();
        if (!admin && visible.isEmpty()) {
            return List.of();
        }
        var query = admin
                ? em.createQuery("SELECT c FROM CommandLog c ORDER BY c.sentAt DESC", CommandLog.class)
                : em.createQuery("SELECT c FROM CommandLog c WHERE c.deviceId IN :ids ORDER BY c.sentAt DESC",
                        CommandLog.class).setParameter("ids", visible);
        return query.setFirstResult(from).setMaxResults(size).getResultList();
    }
}
