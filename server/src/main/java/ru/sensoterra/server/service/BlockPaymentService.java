package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.BlockEntitlement;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.ServerSetting;
import ru.sensoterra.server.repository.BlockEntitlementRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.ServerSettingRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Оплата блока: срок отсчитывается с первой регистрации по паре «ID + ключ» и общий для
 * всех, кто имеет доступ к блоку.
 *
 * Почему не на аккаунте: покупатель подтверждает владение листом, а не аккаунтом. Завёл
 * нового пользователя с тем же ID и ключом — и при оплате на аккаунте срок начался бы
 * заново, то есть платить можно было бы бесконечно.
 *
 * Откуда берётся срок:
 *   1) план самого блока, если админ его назначил («админ настраивает конкретный блок»);
 *   2) иначе дефолт из server_setting — срок или «бессрочно» для всех новых блоков.
 * Оба пути равноправны, поэтому админу не нужно ходить в базу ни за тем, ни за другим.
 *
 * Автономные датчики и датчики, подключённые к блоку, бесплатны: строки оплаты у них не
 * бывает, и гейт по device_type их пропускает.
 */
@Singleton
public class BlockPaymentService {

    public static final String TYPE_BLOCK = "BLOCK";

    static final String KEY_DAYS = "block_paid_days";
    static final String KEY_UNLIMITED = "block_paid_unlimited";

    /** Срок, пока админ не задал свой в настройках. */
    public static final int FALLBACK_DAYS = 365;

    private final BlockEntitlementRepository entitlements;
    private final ServerSettingRepository settings;
    private final DeviceRepository devices;

    public BlockPaymentService(BlockEntitlementRepository entitlements,
                               ServerSettingRepository settings,
                               DeviceRepository devices) {
        this.entitlements = entitlements;
        this.settings = settings;
        this.devices = devices;
    }

    // --- Настройки по умолчанию ---

    public Defaults defaults() {
        return new Defaults(intSetting(KEY_DAYS, FALLBACK_DAYS), boolSetting(KEY_UNLIMITED));
    }

    @Transactional
    public Defaults setDefaults(int days, boolean unlimited) {
        if (days <= 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Days must be positive");
        }
        put(KEY_DAYS, Integer.toString(days));
        put(KEY_UNLIMITED, Boolean.toString(unlimited));
        return defaults();
    }

    // --- Оплата конкретного блока ---

    /**
     * Состояние оплаты блока для показа. Остаток одинаков у всех, кто видит блок: он
     * считается от started_at, а не от того, кто спрашивает.
     */
    public PaymentInfo info(Long chipId) {
        Device device = device(chipId);
        if (!TYPE_BLOCK.equals(device.getDeviceType())) {
            // Датчики не тарифицируются: платить нечего, гейт снят.
            return new PaymentInfo(chipId, false, true, null, null, null, null, true, true);
        }
        BlockEntitlement row = entitlements.findByChipId(chipId).orElse(null);
        // План считаем в одном месте: и первая регистрация, и показ, и пересчёт срока
        // админом обязаны понимать одинаково, иначе интерфейс покажет не тот срок.
        Plan plan = planOf(row, defaults());
        boolean unlimited = plan.unlimited();
        Integer planDays = unlimited ? null : plan.days();
        Instant startedAt = row == null ? null : row.getStartedAt();
        Instant expiresAt = row == null ? null : row.getExpiresAt();
        if (!unlimited && startedAt != null && expiresAt == null) {
            // Строка без явного срока: достраиваем план так же, как это сделала бы первая
            // регистрация, иначе info() врал бы до неё.
            expiresAt = expiryFrom(startedAt, plan.days());
        }
        Long remaining = expiresAt == null ? null
                : Math.max(0L, (expiresAt.toEpochMilli() - System.currentTimeMillis()) / 1000L);
        boolean paid = unlimited || (expiresAt != null && expiresAt.isAfter(Instant.now()));
        return new PaymentInfo(chipId, true, unlimited, planDays, startedAt, expiresAt, remaining,
                paid, startedAt != null);
    }

    /** Оплата действует прямо сейчас? Гейт зовёт это, подробности для интерфейса — info(). */
    public boolean isPaid(Long chipId) {
        return info(chipId).paid();
    }

    /** Блок ли это: только блоки тарифицируются. */
    public boolean chargeable(Long chipId) {
        Device device = devices.findByChipId(chipId).orElse(null);
        return device != null && TYPE_BLOCK.equals(device.getDeviceType());
    }

    /**
     * Запустить отсчёт при первом подтверждении ключом. Идемпотентно: повторные
     * регистрации (новый аккаунт с тем же листом) срок не сдвигают.
     */
    @Transactional
    public void startOnRegistration(Long chipId, Long userId) {
        if (!chargeable(chipId)) {
            return;
        }
        BlockEntitlement row = rowOf(chipId);
        if (row.getStartedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        row.setStartedAt(now);
        row.setStartedByUserId(userId);
        Plan plan = planOf(row, defaults());
        row.setExpiresAt(plan.unlimited() ? null : expiryFrom(now, plan.days()));
        row.setUpdatedAt(now);
        entitlements.save(row);
    }

    /**
     * Назначить план блока: дней (null — дефолт из настроек) либо бессрочно.
     * Если отсчёт уже идёт, срок пересчитывается от его начала: админ задаёт правило,
     * а не точную дату, иначе пришлось бы угадывать, что именно он продлевает.
     */
    @Transactional
    public PaymentInfo setPlan(Long chipId, Integer planDays, Boolean unlimited) {
        requireBlock(chipId);
        if (unlimited == null && planDays == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Days or unlimited is required");
        }
        if (planDays != null && planDays <= 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Days must be positive");
        }
        BlockEntitlement row = rowOf(chipId);
        row.setPlanDays(planDays);
        row.setUnlimited(Boolean.TRUE.equals(unlimited));
        Plan plan = planOf(row, defaults());
        if (plan.unlimited() || row.getStartedAt() == null) {
            row.setExpiresAt(null);
        } else {
            row.setExpiresAt(expiryFrom(row.getStartedAt(), plan.days()));
        }
        row.setUpdatedAt(Instant.now());
        entitlements.save(row);
        return info(chipId);
    }

    /** Продление: дни добавляются к концу текущего срока, истёкший отсчёт идёт заново. */
    @Transactional
    public PaymentInfo extend(Long chipId, int days) {
        requireBlock(chipId);
        if (days <= 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Days must be positive");
        }
        BlockEntitlement row = rowOf(chipId);
        Instant now = Instant.now();
        Instant from = row.getExpiresAt() != null && row.getExpiresAt().isAfter(now)
                ? row.getExpiresAt() : now;
        if (Boolean.TRUE.equals(row.getUnlimited())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Block payment is already unlimited");
        }
        if (row.getStartedAt() == null) {
            // Отсчёта ещё нет: продление в запас, к нему добавится срок плана при
            // первой регистрации.
            Defaults defaults = defaults();
            row.setPlanDays((row.getPlanDays() == null ? defaults.days() : row.getPlanDays()) + days);
            row.setExpiresAt(null);
        } else {
            row.setExpiresAt(from.plusSeconds(days * 86400L));
        }
        row.setUpdatedAt(now);
        entitlements.save(row);
        return info(chipId);
    }

    /**
     * Обнулить отсчёт: блок снова не зарегистрирован, и срок пойдёт заново с первого
     * подтверждения ключом. План сохраняется.
     */
    @Transactional
    public void resetClock(Long chipId) {
        BlockEntitlement row = entitlements.findByChipId(chipId).orElse(null);
        if (row == null) {
            return;
        }
        row.setStartedAt(null);
        row.setStartedByUserId(null);
        row.setExpiresAt(null);
        row.setUpdatedAt(Instant.now());
        entitlements.save(row);
    }

    /** Сбросить отсчёт блоков, которые этот пользователь зарегистрировал первым. */
    @Transactional
    public void resetClocksOf(Long userId) {
        for (BlockEntitlement row : entitlements.findByStartedByUserId(userId)) {
            row.setStartedAt(null);
            row.setStartedByUserId(null);
            row.setExpiresAt(null);
            row.setUpdatedAt(Instant.now());
            entitlements.save(row);
        }
    }

    public List<BlockEntitlement> all() {
        return entitlements.findAll();
    }

    public Optional<BlockEntitlement> findRow(Long chipId) {
        return entitlements.findByChipId(chipId);
    }

    private BlockEntitlement rowOf(Long chipId) {
        return entitlements.findByChipId(chipId).orElseGet(() -> {
            BlockEntitlement row = new BlockEntitlement();
            row.setChipId(chipId);
            return row;
        });
    }

    private Instant expiryFrom(Instant start, int days) {
        return start.plusSeconds(days * 86400L);
    }

    /**
     * Действующий план блока. Свой план — это либо бессрочность, либо конкретное число
     * дней; строка без плана (plan_days = NULL и unlimited = false) означает «бери дефолт»,
     * поэтому записанный false в такой строке дефолт перебить не должен.
     */
    private Plan planOf(BlockEntitlement row, Defaults defaults) {
        boolean own = row != null
                && (Boolean.TRUE.equals(row.getUnlimited()) || row.getPlanDays() != null);
        if (own) {
            return Boolean.TRUE.equals(row.getUnlimited())
                    ? new Plan(true, defaults.days())
                    : new Plan(false, row.getPlanDays());
        }
        return new Plan(defaults.unlimited(), defaults.days());
    }

    private void requireBlock(Long chipId) {
        if (!chargeable(chipId)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Only blocks are paid");
        }
    }

    private Device device(Long chipId) {
        return devices.findByChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    private int intSetting(String key, int fallback) {
        return read(key).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private boolean boolSetting(String key) {
        return read(key).map(Boolean::parseBoolean).orElse(false);
    }

    private Optional<String> read(String key) {
        return settings.findByKey(key).map(ServerSetting::getValue);
    }

    private void put(String key, String value) {
        ServerSetting setting = settings.findByKey(key).orElseGet(() -> {
            ServerSetting s = new ServerSetting();
            s.setKey(key);
            return s;
        });
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        settings.save(setting);
    }

    /** Дефолт для блоков без собственного плана: срок в днях и признак бессрочного. */
    @Serdeable
    public record Defaults(int days, boolean unlimited) {
    }

    /** Действующий план: бессрочно или на N дней от начала отсчёта. */
    private record Plan(boolean unlimited, int days) {
    }

    /**
     * Оплата блока для интерфейса. chargeable = false у датчиков — для них счётчика нет.
     * remainingSeconds — сколько рабочего времени осталось, одно и то же число у всех.
     */
    @Serdeable
    public record PaymentInfo(Long chipId, boolean chargeable, boolean unlimited, Integer planDays,
                              Instant startedAt, Instant expiresAt, Long remainingSeconds,
                              boolean paid, boolean started) {
    }
}
