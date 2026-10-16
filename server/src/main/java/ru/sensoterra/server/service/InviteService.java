package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.DeviceInvite;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.repository.DeviceInviteRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Приглашения на доступ к устройству.
 *
 * Токен одноразовый. У блока живёт sensoterra.invite.ttl-days (по умолчанию 7 дней);
 * у автономного датчика срока нет (expires_at null) — у него нет срока отключения,
 * и ссылка, которой его привязывают к аккаунту, не должна протухать. По ссылке
 * можно либо принять приглашение залогиненным аккаунтом, либо — если аккаунта нет —
 * зарегистрироваться и сразу получить доступ: это и есть «регистрация по коду».
 *
 * Выдать приглашение может любой, кто управляет устройством (VIEWER — нет).
 * Тарифный гейт (features_enabled) при выдаче ссылки не применяется: приглашение
 * открывает доступ, а не возможность управлять.
 */
@Singleton
public class InviteService {

    /** Алфавит без 0/O/1/I, токен идёт в URL-путь — только A-Z и 2-9. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int TOKEN_LENGTH = 32;

    private final DeviceInviteRepository inviteRepository;
    private final AppUserRepository userRepository;
    private final DeviceAccessService access;
    private final UserPrefService prefs;
    private final int ttlDays;

    private final SecureRandom random = new SecureRandom();

    public InviteService(DeviceInviteRepository inviteRepository,
                         AppUserRepository userRepository,
                         DeviceAccessService access,
                         UserPrefService prefs,
                         @Value("${sensoterra.invite.ttl-days:7}") int ttlDays) {
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.prefs = prefs;
        this.ttlDays = ttlDays > 0 ? ttlDays : 7;
    }

    @Transactional
    /**
     * Выдать приглашение. Приглашает пользователь, а не админ: доступ к своему устройству
     * он раздаёт сам, в том числе как «только чтение» (accessRole = VIEWER).
     */
    public DeviceInvite issue(Long chipId, Long createdBy, String accessRole) {
        DeviceInvite invite = new DeviceInvite();
        invite.setToken(uniqueToken());
        // Устройство уже проверено вызывающим (requireShare), но сохраняем и chip_id: FK.
        Device device = access.device(chipId);
        invite.setChipId(device.getChipId());
        invite.setCreatedBy(createdBy);
        invite.setAccessRole(access.normalizeAccessRole(accessRole));
        invite.setCreatedAt(Instant.now());
        // Автономному датчику срок отключения не нужен — ссылка живёт бессрочно;
        // у блока токен протухает через ttl-days.
        if ("INDIVIDUAL_SENSOR".equals(device.getDeviceType())) {
            invite.setExpiresAt(null);
        } else {
            invite.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)));
        }
        return inviteRepository.save(invite);
    }

    public List<DeviceInvite> listFor(Long chipId) {
        access.requireView(chipId);
        return inviteRepository.findByChipIdOrderByCreatedAtDesc(chipId);
    }

    /**
     * Убирает из списка завершённые приглашения устройства: отозванные и истёкшие.
     * Ничего живого не трогает — действующая ссылка должна продолжать работать, а
     * отозванная или просроченная уже не работает никогда.
     *
     * Принятые не удаляются: по ним видно, кому выдавался доступ, и это же объяснение
     * остаётся в device_access. Удаление их было бы уже не уборкой списка, а затиранием
     * истории.
     *
     * @return сколько строк удалено.
     */
    @Transactional
    public int clearFinished(Long chipId) {
        Instant now = Instant.now();
        int removed = 0;
        for (DeviceInvite invite : inviteRepository.findByChipIdOrderByCreatedAtDesc(chipId)) {
            if (invite.getAcceptedAt() != null) {
                continue;
            }
            if (!Boolean.TRUE.equals(invite.getRevoked())
                    && (invite.getExpiresAt() == null || invite.getExpiresAt().isAfter(now))) {
                continue;
            }
            inviteRepository.delete(invite);
            removed++;
        }
        return removed;
    }

    /**
     * Полная очистка: убирает все приглашения устройства, включая принятые и ещё живые.
     *
     * Разница с {@link #clearFinished} — не в том, что удаляется история, а в том, что
     * перестают работать действующие ссылки. Их успевают разослать, и получатель может
     * прийти по ссылке позже; поэтому вызывающий обязан спросить пользователя, а сервер
     * просто честно удаляет.
     *
     * @return сколько строк удалено.
     */
    @Transactional
    public int clearAll(Long chipId) {
        List<DeviceInvite> all = inviteRepository.findByChipIdOrderByCreatedAtDesc(chipId);
        for (DeviceInvite invite : all) {
            inviteRepository.delete(invite);
        }
        return all.size();
    }

    /** Отзыв ссылки: конкретный приглаш Id проверяем, что он принадлежит этому устройству. */
    @Transactional
    public void revoke(Long chipId, Long inviteId) {
        DeviceInvite invite = inviteRepository.findByIdAndChipId(inviteId, chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        invite.setRevoked(Boolean.TRUE);
        inviteRepository.update(invite);
    }

    /**
     * Отозвать все живые ссылки устройства. Нужно при отмене всех регистраций блока:
     * старые ссылки не должны выдавать доступ тому, кто их сохранил.
     */
    @Transactional
    public int revokeAll(Long chipId) {
        int revoked = 0;
        for (DeviceInvite invite : inviteRepository.findByChipIdAndRevokedFalse(chipId)) {
            invite.setRevoked(Boolean.TRUE);
            inviteRepository.update(invite);
            revoked++;
        }
        return revoked;
    }

    /** Принятие приглашения залогиненным аккаунтом. */
    @Transactional
    public void accept(String token, AppUser user) {
        DeviceInvite invite = requireInvite(token);
        if (invite.getCreatedBy() != null && invite.getCreatedBy().equals(user.getId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "You created this invite");
        }
        if (access.hasAccess(user.getId(), invite.getChipId())) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Access to the device is already granted");
        }
        Long invitedBy = invite.getCreatedBy();
        access.grant(user.getId(), invite.getChipId(), invite.getAccessRole());
        // Новичку переходят настройки отображения пригласившего — только по этому блоку и его
        // датчикам, и только там, где у новичка ещё ничего не задано. Настройки администратора
        // не копируются никогда: это личное дело каждого.
        if (invitedBy != null) {
            prefs.copyFrom(invitedBy, user.getId(), invite.getChipId());
        }
        invite.setAcceptedBy(user.getId());
        invite.setAcceptedAt(Instant.now());
        inviteRepository.update(invite);
    }

    /** Логин автора приглашения — в ссылке показываем его, чтобы было видно, кто зовёт. */
    public String usernameOf(Long userId) {
        if (userId == null) {
            return "—";
        }
        return userRepository.findById(userId).map(AppUser::getUsername).orElse("—");
    }

    public DeviceInvite requireInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Invite not found");
        }
        DeviceInvite invite = inviteRepository.findByToken(token.trim()).orElse(null);
        if (invite == null || !invite.isUsable()) {
            // Не различаем «нет такого» и «устарело/отозвано/использовано» — перебора не делаем.
            throw new HttpStatusException(HttpStatus.GONE, "Invite is not usable anymore");
        }
        return invite;
    }

    private String uniqueToken() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String token = randomToken();
            if (inviteRepository.findByToken(token).isEmpty()) {
                return token;
            }
        }
        throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate invite token");
    }

    private String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
