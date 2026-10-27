package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.security.CredentialsPolicy;
import ru.sensoterra.server.security.PasswordEncoder;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.BlockPaymentService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

@Controller("/api/admin/users")
@Secured("ADMIN")
public class AdminUserController {

    private final AppUserRepository userRepository;
    private final ru.sensoterra.server.service.UserPrefService prefs;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceAccessService access;
    private final BlockPaymentService payments;

    public AdminUserController(AppUserRepository userRepository,
                               DeviceRepository deviceRepository,
                               PasswordEncoder passwordEncoder,
                               DeviceAccessService access,
                               BlockPaymentService payments,
                               ru.sensoterra.server.service.UserPrefService prefs) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.access = access;
        this.payments = payments;
        this.prefs = prefs;
    }

    @Get
    public List<UserResponse> list() {
        access.requireAdmin();
        return userRepository.findAll().stream().map(UserResponse::of).toList();
    }

    @Get("/{id}")
    public UserResponse get(Long id) {
        return UserResponse.of(find(id));
    }

    /**
     * Заводит только администраторов. USER заводит сам пользователь через самостоятельную
     * регистрацию и подтверждает свой блок ключом с комплектного листа, поэтому создавать
     * ему аккаунт со стороны сервера незачем, а VIEWER — не роль аккаунта, а доступ
     * «только чтение» к конкретному устройству.
     */
    @Post
    public HttpResponse<UserResponse> create(@Body UserCreateRequest request) {
        String username = CredentialsPolicy.requireUsername(request.username());
        CredentialsPolicy.requirePassword(request.password(), username);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        String role = access.normalizeRole(request.role());
        if (!"ADMIN".equals(role)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN,
                    "Admin creates only ADMIN accounts; users register themselves");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setEnabled(Boolean.TRUE);
        AppUser saved = userRepository.save(user);
        prefs.seedDefaults(saved.getId());
        return HttpResponse.ok(UserResponse.of(saved));
    }

    @Put("/{id}")
    public HttpResponse<UserResponse> update(Long id, @Body UserUpdateRequest request) {
        AppUser user = find(id);
        if (request.password() != null && !request.password().isBlank()) {
            CredentialsPolicy.requirePassword(request.password(), user.getUsername());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.role() != null && !request.role().isBlank()) {
            String role = access.normalizeRole(request.role());
            if ("ADMIN".equals(user.getRole()) && !"ADMIN".equals(role)) {
                long admins = userRepository.findAll().stream()
                        .filter(u -> "ADMIN".equals(u.getRole()))
                        .count();
                if (admins <= 1) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "The last admin cannot be demoted");
                }
            }
            user.setRole(role);
        }
        if (request.enabled() != null) {
            if (!request.enabled() && "ADMIN".equals(user.getRole())) {
                long admins = userRepository.findAll().stream()
                        .filter(u -> "ADMIN".equals(u.getRole()) && Boolean.TRUE.equals(u.getEnabled()))
                        .count();
                if (admins <= 1) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "The last admin cannot be disabled");
                }
            }
            user.setEnabled(request.enabled());
        }
        return HttpResponse.ok(UserResponse.of(userRepository.update(user)));
    }

    @Delete("/{id}")
    public HttpResponse<?> delete(Long id) {
        AppUser user = find(id);
        if (id.equals(access.currentUser().getId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Cannot delete your own account");
        }
        if ("ADMIN".equals(user.getRole())) {
            long admins = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equals(u.getRole()))
                    .count();
            if (admins <= 1) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "The last admin cannot be deleted");
            }
        }
        access.revokeAll(id);
        // Удалённого регистратора больше нет — отсчёт оплаты его блоков обнуляется,
        // иначе блок остался бы оплаченным до конца несуществующим пользователем.
        payments.resetClocksOf(id);
        userRepository.delete(user);
        return HttpResponse.noContent();
    }

    @Get("/{id}/devices")
    public List<Device> devices(Long id) {
        find(id);
        var ids = access.chipsOfUser(id);
        return deviceRepository.findAll().stream()
                .filter(d -> ids.contains(d.getChipId()))
                .toList();
    }

    /**
     * Выдаёт доступ к устройству, при необходимости заводя его. Устройство с присвоенным
     * @Id нельзя сохранять через save() вне транзакции — Hibernate делает persist и падает
     * на duplicate key, поэтому ветки разделены, а метод транзакционный.
     */
    @Post("/{id}/devices")
    @Transactional
    public HttpResponse<Device> assignDevice(Long id, @Body DeviceAssignRequest request) {
        AppUser user = find(id);
        var existing = deviceRepository.findByChipId(request.chipId());
        Device device;
        if (existing.isPresent()) {
            device = existing.get();
        } else {
            device = new Device();
            device.setChipId(request.chipId());
            device.setDeviceType(request.deviceType() != null ? request.deviceType() : "BLOCK");
            deviceRepository.save(device);
        }
        if (DeviceAccessService.ACCESS_OWNER.equalsIgnoreCase(String.valueOf(request.accessRole()).trim())) {
            // Admin-only ownership transfer: the previous owner stays with USER rights.
            access.setOwner(user.getId(), device.getChipId());
        } else {
            // Admin may also downgrade the owner (forceAccessRole); guests and invites can't.
            access.forceAccessRole(user.getId(), device.getChipId(),
                    request.accessRole() != null ? request.accessRole() : DeviceAccessService.ACCESS_USER);
        }
        return HttpResponse.ok(device);
    }

    @Delete("/{id}/devices/{chipId}")
    public HttpResponse<?> unassignDevice(Long id, Long chipId) {
        access.revoke(id, chipId);
        return HttpResponse.noContent();
    }

    /**
     * @Secured("ADMIN") смотрит роль в JWT, поэтому после понижения в БД старый токен
     * ещё сутки проходил бы. Здесь роль сверяется с БД — на точку входа для всех
     * методов контроллера (каждый начинает с find(id), кроме list()).
     */
    private AppUser find(Long id) {
        access.requireAdmin();
        return byId(id);
    }

    private AppUser byId(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * Оплаты у аккаунта нет: срок принадлежит блоку (BlockPaymentService), поэтому в
     * карточке пользователя платных полей нет.
     */
    @Serdeable
    public record UserResponse(Long id, String username, String email, String role, boolean enabled) {

        static UserResponse of(AppUser u) {
            return new UserResponse(
                    u.getId(),
                    u.getUsername(),
                    u.getEmail(),
                    u.getRole(),
                    !Boolean.FALSE.equals(u.getEnabled()));
        }
    }

    @Serdeable
    public record UserCreateRequest(String username, String password, String role) {
    }

    @Serdeable
    public record UserUpdateRequest(String password, String role, Boolean enabled) {
    }

    @Serdeable
    public record DeviceAssignRequest(Long chipId, String deviceType, String accessRole) {
    }
}
