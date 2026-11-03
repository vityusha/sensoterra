package ru.sensoterra.server.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import ru.sensoterra.server.entity.Alarm;
import ru.sensoterra.server.entity.AlarmRead;
import ru.sensoterra.server.entity.AlarmSeen;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.AlarmService;
import ru.sensoterra.server.service.DeviceAccessService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alarm list of the caller's devices (alarms page). Everyone who sees a device sees its alarms;
 * "read" (normal font) and "seen" (bell dot) are per account; deleting a finished alarm removes it
 * for everyone and needs control of the device.
 */
@Controller("/api/alarms")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class AlarmController {

    static final int MAX_LIMIT = 500;

    private final EntityManager em;
    private final DeviceAccessService access;
    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;

    public AlarmController(EntityManager em, DeviceAccessService access,
                           DeviceRepository deviceRepository, SensorRepository sensorRepository) {
        this.em = em;
        this.access = access;
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
    }

    /** Alarms of one type, newest start first. */
    @Get
    @Transactional
    public List<AlarmView> list(@QueryValue String type,
                                @QueryValue(defaultValue = "200") int limit,
                                @QueryValue(defaultValue = "0") int offset) {
        String alarmType = requireType(type);
        Scope scope = scope();
        if (scope.empty()) {
            return List.of();
        }
        var query = em.createQuery("SELECT a FROM Alarm a WHERE a.alarmType = :type"
                        + (scope.admin ? "" : " AND a.deviceId IN :ids")
                        + " ORDER BY a.startedAt DESC, a.id DESC", Alarm.class)
                .setParameter("type", alarmType);
        if (!scope.admin) {
            query.setParameter("ids", scope.ids);
        }
        List<Alarm> alarms = query
                .setFirstResult(Math.max(offset, 0))
                .setMaxResults(Math.max(1, Math.min(limit, MAX_LIMIT)))
                .getResultList();
        if (alarms.isEmpty()) {
            return List.of();
        }
        Long userId = access.currentUser().getId();
        Set<Long> read = new HashSet<>(em.createQuery(
                        "SELECT r.id.alarmId FROM AlarmRead r WHERE r.id.userId = :uid AND r.id.alarmId IN :ids",
                        Long.class)
                .setParameter("uid", userId)
                .setParameter("ids", alarms.stream().map(Alarm::getId).toList())
                .getResultList());
        Map<Long, Boolean> control = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        Map<Long, Integer> zones = new HashMap<>();
        List<AlarmView> result = new ArrayList<>();
        for (Alarm a : alarms) {
            boolean finished = a.getEndedAt() != null;
            boolean canDelete = finished && control.computeIfAbsent(a.getDeviceId(), access::canControl);
            String name = names.computeIfAbsent(a.getChipId(), chip -> nameOf(chip, a.getAlarmType()));
            Integer zone = zones.computeIfAbsent(a.getDeviceId(), chip -> deviceRepository.findById(chip)
                    .map(Device::getTzOffsetMinutes).orElse(null));
            result.add(new AlarmView(a.getId(), a.getAlarmType(), a.getChipId(), a.getDeviceId(), name, zone,
                    a.getStartedAt(), a.getEndedAt(), a.getValue(),
                    finished && read.contains(a.getId()), canDelete));
        }
        return result;
    }

    /**
     * For the bell: alarms started or finished since the caller last opened the alarms page,
     * and the open alarms per type.
     */
    @Get("/summary")
    @Transactional
    public Summary summary() {
        Scope scope = scope();
        if (scope.empty()) {
            return new Summary(0, Map.of());
        }
        AlarmSeen seen = em.find(AlarmSeen.class, access.currentUser().getId());
        Instant since = seen != null ? seen.getSeenAt() : Instant.EPOCH;
        var unseen = em.createQuery("SELECT count(a) FROM Alarm a"
                        + " WHERE (a.startedAt > :since OR a.endedAt > :since)"
                        + (scope.admin ? "" : " AND a.deviceId IN :ids"), Long.class)
                .setParameter("since", since);
        var open = em.createQuery("SELECT a.alarmType, count(a) FROM Alarm a WHERE a.endedAt IS NULL"
                        + (scope.admin ? "" : " AND a.deviceId IN :ids")
                        + " GROUP BY a.alarmType", Object[].class);
        if (!scope.admin) {
            unseen.setParameter("ids", scope.ids);
            open.setParameter("ids", scope.ids);
        }
        Map<String, Long> openByType = new LinkedHashMap<>();
        for (Object[] row : open.getResultList()) {
            openByType.put((String) row[0], ((Number) row[1]).longValue());
        }
        return new Summary(unseen.getSingleResult(), openByType);
    }

    /** The caller opened the alarms page: the bell's dot goes out. */
    @Post("/seen")
    @Transactional
    public HttpResponse<?> seen() {
        Long userId = access.currentUser().getId();
        AlarmSeen seen = em.find(AlarmSeen.class, userId);
        if (seen == null) {
            seen = new AlarmSeen();
            seen.setUserId(userId);
            seen.setSeenAt(Instant.now());
            em.persist(seen);
        } else {
            seen.setSeenAt(Instant.now());
        }
        return HttpResponse.noContent();
    }

    /** The caller clicked a finished alarm: shown in normal font from now on. */
    @Post("/{id}/read")
    @Transactional
    public HttpResponse<?> markRead(Long id) {
        Alarm alarm = visibleAlarm(id);
        if (alarm.getEndedAt() == null) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Alarm is still active");
        }
        AlarmRead.Key key = new AlarmRead.Key(id, access.currentUser().getId());
        if (em.find(AlarmRead.class, key) == null) {
            em.persist(new AlarmRead(key));
        }
        return HttpResponse.noContent();
    }

    /** Marks every finished alarm of one type as read by the caller; active ones stay bold. */
    @Post("/read")
    @Transactional
    public ReadResult markAllRead(@QueryValue String type) {
        String alarmType = requireType(type);
        Scope scope = scope();
        if (scope.empty()) {
            return new ReadResult(0);
        }
        Long userId = access.currentUser().getId();
        var query = em.createQuery("SELECT a.id FROM Alarm a WHERE a.endedAt IS NOT NULL AND a.alarmType = :type"
                        + " AND a.id NOT IN (SELECT r.id.alarmId FROM AlarmRead r WHERE r.id.userId = :uid)"
                        + (scope.admin ? "" : " AND a.deviceId IN :ids"), Long.class)
                .setParameter("type", alarmType)
                .setParameter("uid", userId);
        if (!scope.admin) {
            query.setParameter("ids", scope.ids);
        }
        List<Long> ids = query.getResultList();
        for (Long id : ids) {
            em.persist(new AlarmRead(new AlarmRead.Key(id, userId)));
        }
        return new ReadResult(ids.size());
    }

    /** Deletes one finished alarm (for everyone). */
    @Delete("/{id}")
    @Transactional
    public HttpResponse<?> delete(Long id) {
        Alarm alarm = visibleAlarm(id);
        if (alarm.getEndedAt() == null) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Alarm is still active");
        }
        if (!access.canControl(alarm.getDeviceId())) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Read-only access to this device");
        }
        remove(List.of(alarm.getId()));
        return HttpResponse.noContent();
    }

    /**
     * Deletes the finished alarms the caller has read, of one type or of all types; alarms of
     * devices the caller can't control are kept.
     */
    @Delete
    @Transactional
    public DeleteResult deleteRead(@Nullable @QueryValue String type) {
        String alarmType = type == null || type.isBlank() ? null : requireType(type);
        Scope scope = scope();
        if (scope.empty()) {
            return new DeleteResult(0);
        }
        var query = em.createQuery("SELECT a FROM Alarm a WHERE a.endedAt IS NOT NULL"
                        + " AND a.id IN (SELECT r.id.alarmId FROM AlarmRead r WHERE r.id.userId = :uid)"
                        + (alarmType == null ? "" : " AND a.alarmType = :type")
                        + (scope.admin ? "" : " AND a.deviceId IN :ids"), Alarm.class)
                .setParameter("uid", access.currentUser().getId());
        if (alarmType != null) {
            query.setParameter("type", alarmType);
        }
        if (!scope.admin) {
            query.setParameter("ids", scope.ids);
        }
        Map<Long, Boolean> control = new HashMap<>();
        List<Long> ids = query.getResultList().stream()
                .filter(a -> control.computeIfAbsent(a.getDeviceId(), access::canControl))
                .map(Alarm::getId)
                .toList();
        remove(ids);
        return new DeleteResult(ids.size());
    }

    private void remove(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        // alarm_read cascades in PostgreSQL; the H2 test schema has no foreign keys.
        em.createQuery("DELETE FROM AlarmRead r WHERE r.id.alarmId IN :ids").setParameter("ids", ids).executeUpdate();
        em.createQuery("DELETE FROM Alarm a WHERE a.id IN :ids").setParameter("ids", ids).executeUpdate();
    }

    private Alarm visibleAlarm(Long id) {
        Alarm alarm = em.find(Alarm.class, id);
        if (alarm == null || !access.canView(alarm.getDeviceId())) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Alarm not found");
        }
        return alarm;
    }

    private static String requireType(String type) {
        if (type == null || !AlarmService.TYPES.contains(type)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown alarm type: " + type);
        }
        return type;
    }

    /** Device name for blocks, the firmware name of the latest data row for sensors. */
    private String nameOf(Long chipId, String type) {
        if (AlarmService.OFFLINE.equals(type)) {
            return deviceRepository.findById(chipId).map(Device::getName).orElse(null);
        }
        return sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(chipId).stream()
                .map(Sensor::getName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElseGet(() -> deviceRepository.findById(chipId).map(Device::getName).orElse(null));
    }

    private Scope scope() {
        boolean admin = access.isAdmin();
        return new Scope(admin, admin ? Set.of() : access.visibleChipIds());
    }

    private record Scope(boolean admin, Set<Long> ids) {
        boolean empty() {
            return !admin && ids.isEmpty();
        }
    }

    @Serdeable
    public record AlarmView(Long id, String type, Long chipId, Long deviceId, String name,
                            Integer tzOffsetMinutes, Instant startedAt, Instant endedAt, Double value,
                            boolean read, boolean canDelete) {
    }

    @Serdeable
    public record Summary(long unseen, Map<String, Long> open) {
    }

    @Serdeable
    public record DeleteResult(int deleted) {
    }

    @Serdeable
    public record ReadResult(int read) {
    }
}
