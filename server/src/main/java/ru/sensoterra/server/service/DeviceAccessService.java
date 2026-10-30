package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.DeviceAccess;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.repository.DeviceAccessRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Единственная точка решения о правах. Роль аккаунта — ADMIN/USER, а права на конкретное
 * устройство живут в device_access: OWNER (owner: control + managing access and invites),
 * USER (управление) или VIEWER (только чтение).
 *
 * ADMIN — видит и управляет всеми устройствами.
 * USER  — видит и управляет теми, к которым у него доступ с правом USER. Право «только
 *         чтение» на чужом блоке даёт VIEWER на этом устройстве — и не трогает права
 *         на свои собственные блоки.
 *
 * Устройство, к которому нет доступа, отдаётся как 404: существование чужих блоков
 * и датчиков не раскрываем.
 */
@Singleton
public class DeviceAccessService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    /** Права на устройство: полные или только чтение. */
    public static final String ACCESS_USER = "USER";
    public static final String ACCESS_VIEWER = "VIEWER";
    /**
     * Owner: full control plus the only non-admin who may grant/revoke access and send invites.
     * At most one per device (partial unique index, V3). Set by the first key claim or by an
     * admin; never through grant() or invites, and cannot be revoked or downgraded by guests.
     */
    public static final String ACCESS_OWNER = "OWNER";

    /** OWNER and USER both control the device; compare through this, not with ACCESS_USER. */
    public static boolean canControlRole(String accessRole) {
        return ACCESS_OWNER.equals(accessRole) || ACCESS_USER.equals(accessRole);
    }

    /** Управление блоками — платная функция, автономные датчики не тарифицируются. */
    private static final String TYPE_BLOCK = "BLOCK";

    private final DeviceAccessRepository accessRepository;
    private final AppUserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final RelayRepository relayRepository;
    private final SensorRepository sensorRepository;
    private final SecurityService securityService;
    private final BlockPaymentService payments;

    public DeviceAccessService(DeviceAccessRepository accessRepository,
                               AppUserRepository userRepository,
                               DeviceRepository deviceRepository,
                               RelayRepository relayRepository,
                               SensorRepository sensorRepository,
                               SecurityService securityService,
                               BlockPaymentService payments) {
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.relayRepository = relayRepository;
        this.sensorRepository = sensorRepository;
        this.securityService = securityService;
        this.payments = payments;
    }

    /**
     * Аккаунт текущего запроса. Статус читаем из БД, а не из токена: отключённый или
     * удалённый аккаунт теряет доступ сразу, а не когда истекут 24 часа жизни JWT.
     */
    public AppUser currentUser() {
        String username = securityService.username().orElse(null);
        if (username == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        return user;
    }

    /** Роль тоже берём из БД: смена роли действует сразу, без ожидания протухания токена. */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role());
    }

    /** Права текущего аккаунта на устройство: ACCESS_USER, ACCESS_VIEWER или null. */
    public String accessRoleOf(Long chipId) {
        AppUser user = currentUser();
        return accessRepository.findByUserId(user.getId()).stream()
                .filter(a -> chipId != null && chipId.equals(a.getChipId()))
                .map(DeviceAccess::getAccessRole)
                .findFirst()
                .orElse(null);
    }

    public String role() {
        return currentUser().getRole();
    }

    /** Явная проверка правды роли ADMIN — для эндпоинтов с @Secured("ADMIN"). */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Admin role is required");
        }
    }

    /**
     * Аккаунт жив и включён — для WebSocket-сессий, где нет HTTP-запроса и SecurityService.
     * Существующая сессия отключённого пользователя перестаёт получать события.
     */
    public boolean accountActive(String username) {
        AppUser user = userRepository.findByUsername(username).orElse(null);
        return user != null && !Boolean.FALSE.equals(user.getEnabled());
    }

    public AppUser byId(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public Device device(Long chipId) {
        return deviceRepository.findByChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    /** Чипы, видимые текущему пользователю; админу — все. */
    public Set<Long> visibleChipIds() {
        if (isAdmin()) {
            return allChipIds();
        }
        return chipsOfUser(currentUser().getId());
    }

    /**
     * Чипы, доступные конкретному аккаунту — без админ-исключения, даже если запрос
     * делает администратор (иначе в списке устройств чужого аккаунта были бы все блоки).
     */
    public Set<Long> chipsOfUser(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (DeviceAccess access : accessRepository.findByUserId(userId)) {
            ids.add(access.getChipId());
        }
        return ids;
    }

    /**
     * Чипы, видимые аккаунту по имени — для WebSocket-сессий, где нет HTTP-запроса
     * и, значит, нет SecurityService.
     */
    public Set<Long> visibleChipIdsFor(String username) {
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return Set.of();
        }
        if (ROLE_ADMIN.equals(user.getRole())) {
            return allChipIds();
        }
        return chipsOfUser(user.getId());
    }

    public boolean isAdmin(String username) {
        AppUser user = userRepository.findByUsername(username).orElse(null);
        return user != null && ROLE_ADMIN.equals(user.getRole());
    }

    private Set<Long> allChipIds() {
        Set<Long> ids = new LinkedHashSet<>();
        List<Device> devices = deviceRepository.findAll();
        for (Device d : devices) {
            ids.add(d.getChipId());
        }
        return ids;
    }

    public boolean canView(Long chipId) {
        if (chipId == null) {
            return false;
        }
        return isAdmin() || accessRepository.findByUserId(currentUser().getId()).stream()
                .anyMatch(a -> chipId.equals(a.getChipId()));
    }

    /** Управление каналами и изменение настроек устройства — право хранится на устройстве. */
    public boolean canControl(Long chipId) {
        return isAdmin() || canControlRole(accessRoleOf(chipId));
    }

    public boolean isOwner(Long chipId) {
        return ACCESS_OWNER.equals(accessRoleOf(chipId));
    }

    /**
     * Managing who has access (grant, revoke, change rights): the owner or an admin.
     * Gated by payment like requireControl was before (invites are not: see requireShare).
     */
    public void requireManageAccess(Long chipId) {
        if (isAdmin()) {
            return;
        }
        requireView(chipId);
        if (!isOwner(chipId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Only the device owner can manage access");
        }
    }

    public void requireView(Long chipId) {
        if (!canView(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        requirePaid(chipId);
    }

    public void requireControl(Long chipId) {
        requireView(chipId);
        if (!canControl(chipId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Read-only access to this device");
        }
    }

    /**
     * Оплата блока. Срок отсчитывается с первого подтверждения ключом и общий для всех,
     * кто имеет доступ к блоку, поэтому проверка не зависит от того, кто именно смотрит.
     *
     * Закрываются не только управление и настройки, но и чтение: состояние, графики и
     * статистика датчиков блока — тоже платная часть. Сам список устройств остаётся
     * открытым, иначе нельзя понять, что блок вообще есть и пора оплатить.
     *
     * ADMIN обходит гейт. Автономные датчики и датчики, подключённые к блоку, отдельными
     * устройствами не тарифицируются.
     */
    private void requirePaid(Long chipId) {
        if (isAdmin() || !payments.chargeable(chipId)) {
            return;
        }
        BlockPaymentService.PaymentInfo info = payments.info(chipId);
        if (info.paid()) {
            return;
        }
        if (!info.started()) {
            throw new HttpStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Block is not registered yet — confirm ID and key from the sheet");
        }
        throw new HttpStatusException(HttpStatus.PAYMENT_REQUIRED,
                "Block payment expired on " + info.expiresAt());
    }

    /** Проверка прав по объекту, привязанному к устройству (реле, датчик, слот). */
    public void requireViewDevice(Long chipId) {
        requireView(chipId);
    }

    public void requireControlDevice(Long chipId) {
        requireControl(chipId);
    }

    /**
     * Право «поделиться устройством»: роль должна позволять управление, но тарифная
     * лицензия не требуется — приглашение открывает доступ, а не возможность управлять.
     * Иначе неоплативший пользователь не смог бы пригласить того, кто оплатит.
     *
     * Приглашения рассылает владелец, а не админ: админ выдаёт доступ напрямую
     * (POST /api/admin/users/{id}/devices) и не должен подменять собой хозяина блока.
     */
    public void requireShare(Long chipId) {
        if (isAdmin()) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN,
                    "Invitations are sent by the device owner, admin grants access directly");
        }
        // Именно canView, а не requireView: приглашение выдаётся и до оплаты блока,
        // иначе неоплативший не смог бы позвать того, кто оплатит.
        if (!canView(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        if (!isOwner(chipId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Only the device owner can share it");
        }
    }

    /** Устройство, которому принадлежит реле. */
    public Long chipOfRelay(Long relayId) {
        Relay relay = relayRepository.findById(relayId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Relay not found"));
        return relay.getDeviceId();
    }

    /**
     * Устройство, которому принадлежит датчик. У датчика на блоке это блок,
     * у автономного — он сам заведён как устройство INDIVIDUAL_SENSOR.
     */
    public Long chipOfSensor(Long sensorId) {
        Sensor sensor = sensorRepository.findById(sensorId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Sensor not found"));
        if (sensor.getDeviceId() != null) {
            return sensor.getDeviceId();
        }
        return sensor.getSensorChipId();
    }

    public void requireRelayControl(Long relayId) {
        requireControl(chipOfRelay(relayId));
    }

    public void requireRelayView(Long relayId) {
        requireView(chipOfRelay(relayId));
    }

    public void requireSensorControl(Long sensorId) {
        requireControl(chipOfSensor(sensorId));
    }

    /**
     * Право управлять датчиком по его чипу: автономный датчик — он сам, датчик слота —
     * его блок. Принцип тот же, что и в UserPrefService.requireControlSensor.
     */
    public void requireSensorControlByChip(Long sensorChipId) {
        if (isAdmin()) {
            return;
        }
        for (Sensor sensor : sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(sensorChipId)) {
            if (sensor.getDeviceId() != null && canControl(sensor.getDeviceId())) {
                return;
            }
        }
        if (canControl(sensorChipId)) {
            return;
        }
        throw new HttpStatusException(HttpStatus.FORBIDDEN, "Read-only access to this device");
    }

    public void requireSensorView(Long sensorId) {
        requireView(chipOfSensor(sensorId));
    }

    public List<DeviceAccess> accessOf(Long userId) {
        return accessRepository.findByUserId(userId);
    }

    public boolean hasAccess(Long userId, Long chipId) {
        return accessRepository.findByUserId(userId).stream()
                .anyMatch(a -> chipId.equals(a.getChipId()));
    }

    /** Owner logins for the device list: chipId -> username. */
    public Map<Long, String> ownerUsernames(Collection<Long> chipIds) {
        if (chipIds == null || chipIds.isEmpty()) {
            return Map.of();
        }
        List<DeviceAccess> owners = accessRepository.findByChipIdIn(chipIds).stream()
                .filter(a -> ACCESS_OWNER.equals(a.getAccessRole()))
                .toList();
        if (owners.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (DeviceAccess owner : owners) {
            userIds.add(owner.getUserId());
        }
        Map<Long, String> usernames = new HashMap<>();
        for (AppUser user : userRepository.findByIdIn(userIds)) {
            usernames.put(user.getId(), user.getUsername());
        }
        Map<Long, String> result = new HashMap<>();
        for (DeviceAccess owner : owners) {
            String username = usernames.get(owner.getUserId());
            if (username != null) {
                result.put(owner.getChipId(), username);
            }
        }
        return result;
    }

    /**
     * Выдать доступ к устройству. Права можно поменять повторным вызовом — так владелец
     * понижает гостя до «только чтение» и обратно.
     */
    @Transactional
    public void grant(Long userId, Long chipId, String accessRole) {
        String role = normalizeAccessRole(accessRole);
        if (ACCESS_USER.equals(role) && !hasOwner(chipId)) {
            // Same rule as migration V3: a device without an owner gets the first account with
            // full control (e.g. an admin assigning a block without a key claim) — otherwise
            // nobody but admins could share it.
            setOwner(userId, chipId);
            return;
        }
        DeviceAccess existing = accessRepository.findByUserId(userId).stream()
                .filter(a -> chipId.equals(a.getChipId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            // The owner keeps OWNER: e.g. accepting a VIEWER invite to one's own block must not
            // downgrade it. Ownership moves only through setOwner().
            if (!ACCESS_OWNER.equals(existing.getAccessRole()) && !role.equals(existing.getAccessRole())) {
                existing.setAccessRole(role);
                accessRepository.save(existing);
            }
            return;
        }
        DeviceAccess access = new DeviceAccess();
        access.setUserId(userId);
        access.setChipId(chipId);
        access.setAccessRole(role);
        accessRepository.save(access);
    }

    /**
     * Admin override: sets USER/VIEWER even on the owner's row (the device is left without an
     * owner until the next account with full control gets it, see grant()).
     */
    @Transactional
    public void forceAccessRole(Long userId, Long chipId, String accessRole) {
        String role = normalizeAccessRole(accessRole);
        DeviceAccess existing = accessRepository.findByUserId(userId).stream()
                .filter(a -> chipId.equals(a.getChipId()))
                .findFirst()
                .orElse(null);
        if (existing == null || !ACCESS_OWNER.equals(existing.getAccessRole())) {
            grant(userId, chipId, role);
            return;
        }
        existing.setAccessRole(role);
        accessRepository.save(existing);
    }

    public boolean hasOwner(Long chipId) {
        return accessRepository.findByChipId(chipId).stream()
                .anyMatch(a -> ACCESS_OWNER.equals(a.getAccessRole()));
    }

    /** Makes userId the owner; the previous owner (if any) keeps full control as USER. */
    @Transactional
    public void setOwner(Long userId, Long chipId) {
        DeviceAccess target = null;
        for (DeviceAccess a : accessRepository.findByChipId(chipId)) {
            if (userId.equals(a.getUserId())) {
                target = a;
            } else if (ACCESS_OWNER.equals(a.getAccessRole())) {
                a.setAccessRole(ACCESS_USER);
                // Flush the demotion first: the partial unique index allows one OWNER per device.
                accessRepository.saveAndFlush(a);
            }
        }
        if (target == null) {
            target = new DeviceAccess();
            target.setUserId(userId);
            target.setChipId(chipId);
        }
        target.setAccessRole(ACCESS_OWNER);
        accessRepository.save(target);
    }

    @Transactional
    public void grant(Long userId, Long chipId) {
        grant(userId, chipId, ACCESS_USER);
    }

    /** Права доступа конкретного аккаунта к устройству — для списка в карточке блока. */
    public String accessRoleOf(Long userId, Long chipId) {
        return accessRepository.findByUserId(userId).stream()
                .filter(a -> chipId.equals(a.getChipId()))
                .map(DeviceAccess::getAccessRole)
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public void revoke(Long userId, Long chipId) {
        accessRepository.deleteByUserIdAndChipId(userId, chipId);
    }

    @Transactional
    public void revokeAll(Long userId) {
        accessRepository.deleteByUserId(userId);
    }

    /** Снять доступ всех, кроме никого: отмена всех регистраций устройства целиком. */
    @Transactional
    public void revokeAllForDevice(Long chipId) {
        accessRepository.deleteByChipId(chipId);
    }

    /**
     * Приводит роль аккаунта к допустимому виду. Роль у аккаунта только одна из двух:
     * ADMIN или USER. VIEWER — не роль аккаунта, а право на конкретное устройство.
     */
    public String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_USER;
        }
        String value = role.trim().toUpperCase();
        if ("CLIENT".equals(value)) {
            return ROLE_USER;
        }
        if ("VIEWER".equals(value)) {
            // Старые VIEWER-аккаунты после V19 — обычные USER с правом «только чтение»
            // на выданных им устройствах (миграция переносит это в device_access).
            return ROLE_USER;
        }
        if (!ROLE_ADMIN.equals(value) && !ROLE_USER.equals(value)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown role: " + role);
        }
        return value;
    }

    /** Приводит права доступа к устройству к виду USER или VIEWER. */
    public String normalizeAccessRole(String role) {
        if (role == null || role.isBlank()) {
            return ACCESS_USER;
        }
        String value = role.trim().toUpperCase();
        if (ACCESS_OWNER.equals(value)) {
            // Ownership is not granted like a normal right: see setOwner().
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "OWNER can't be granted here");
        }
        if (!ACCESS_USER.equals(value) && !ACCESS_VIEWER.equals(value)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown access role: " + role);
        }
        return value;
    }

    /** Все аккаунты (кроме админов), которым виден этот блок — для списка доступа. */
    public List<AppUser> accountsWithAccess(Long chipId) {
        return accessRepository.findByChipId(chipId).stream()
                .map(a -> userRepository.findById(a.getUserId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(user -> !ROLE_ADMIN.equals(user.getRole()))
                .toList();
    }
}
