package ru.sensoterra.server.service;

import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sensoterra.server.entity.AdminAuditEntry;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AdminAuditRepository;

import java.time.Instant;
import java.util.List;

/**
 * Journal of admin sign-ins and admin actions (table admin_audit). The CLI writes its own
 * entries over JDBC with the same event names (AdminCli).
 */
@Singleton
public class AdminAuditService {

    public static final String LOGIN_PASSWORD_OK = "LOGIN_PASSWORD_OK";
    public static final String LOGIN_EXTERNAL_OK = "LOGIN_EXTERNAL_OK";
    public static final String MFA_OK = "MFA_OK";
    public static final String MFA_FAIL = "MFA_FAIL";
    public static final String MFA_ENROLLED = "MFA_ENROLLED";
    public static final String RECOVERY_CODE_USED = "RECOVERY_CODE_USED";
    public static final String SESSION_REFUSED = "SESSION_REFUSED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String DEVICE_GRANTED = "DEVICE_GRANTED";
    public static final String DEVICE_REVOKED = "DEVICE_REVOKED";
    public static final String SETTINGS_CHANGED = "SETTINGS_CHANGED";
    public static final String DEVICE_KEY_READ = "DEVICE_KEY_READ";
    public static final String DEVICE_KEY_REGENERATED = "DEVICE_KEY_REGENERATED";
    public static final String DEVICE_DELETED = "DEVICE_DELETED";
    /** An owner granted / revoked admins' full access to their devices (user = the owner). */
    public static final String OWNER_ADMIN_ACCESS_GRANTED = "OWNER_ADMIN_ACCESS_ON";
    public static final String OWNER_ADMIN_ACCESS_REVOKED = "OWNER_ADMIN_ACCESS_OFF";

    public static final int MAX_PAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminAuditRepository repository;
    private final DeviceAccessService access;

    public AdminAuditService(AdminAuditRepository repository, DeviceAccessService access) {
        this.repository = repository;
        this.access = access;
    }

    /** Action of the admin making the current request. */
    public void action(String event, String detail) {
        record(access.currentUser(), event, detail);
    }

    /** Own transaction: a failed sign-in step rolls back its caller but must stay in the log. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(AppUser user, String event, String detail) {
        AdminAuditEntry entry = new AdminAuditEntry();
        if (user != null) {
            entry.setUserId(user.getId());
            entry.setUsername(user.getUsername());
        }
        entry.setEvent(event);
        entry.setIp(clientAddress());
        entry.setDetail(detail == null ? null : cut(detail, 255));
        entry.setCreatedAt(Instant.now());
        repository.save(entry);
        log.info("Admin audit: {} user='{}' {}", event, entry.getUsername(), detail == null ? "" : detail);
    }

    public List<AdminAuditEntry> page(Long beforeId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        long before = beforeId == null ? Long.MAX_VALUE : beforeId;
        return repository.findByIdLessThanOrderByIdDesc(before, Pageable.from(0, size));
    }

    /**
     * Forwarded chain as nginx passes it, else the socket address. The leftmost entries of
     * X-Forwarded-For are whatever the client sent, so this is for reading, not for decisions.
     */
    private static String clientAddress() {
        HttpRequest<?> request = ServerRequestContext.currentRequest().orElse(null);
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeaders().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return cut(forwarded, 64);
        }
        var remote = request.getRemoteAddress();
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
