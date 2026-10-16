package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.DeviceAccess;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.repository.DeviceAccessRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ключ устройства из комплектного листа. Продавец отдаёт покупателю блок вместе с листом,
 * где напечатаны ID (chip_id) и ключ. Покупатель регистрируется и вводит эту пару —
 * устройство привязывается к его аккаунту.
 *
 * Ключ хранится в открытом виде: админ должен видеть и копировать его, чтобы напечатать
 * лист, и может перевыпустить (старый лист после этого перестаёт работать).
 *
 * Ключ многоразовой и не «сгорает»: он подтверждает владение, поэтому по нему же
 * устройство можно перепривязать (например, при переезде на другой аккаунт).
 */
@Singleton
public class DeviceKeyService {

    /** Алфавит без 0/O, 1/I и похожих символов — ключ диктуют вслух по телефону. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int GROUPS = 3;
    private static final int GROUP_LEN = 4;

    private final SecureRandom random = new SecureRandom();

    private final DeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final DeviceAccessRepository accessRepository;
    private final BlockPaymentService payments;

    public DeviceKeyService(DeviceRepository deviceRepository,
                            AppUserRepository userRepository,
                            DeviceAccessRepository accessRepository,
                            BlockPaymentService payments) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.payments = payments;
    }

    /** Ключ устройства, при необходимости выпуская его при первом обращении. */
    @Transactional
    public String keyOf(Long chipId) {
        Device device = deviceRepository.findByChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (device.getDeviceKey() == null || device.getDeviceKey().isBlank()) {
            device.setDeviceKey(generate());
            deviceRepository.save(device);
        }
        return device.getDeviceKey();
    }

    /** Перевыпуск ключа: старый лист перестаёт работать. Прежние привязки сохраняются. */
    @Transactional
    public String regenerate(Long chipId) {
        Device device = deviceRepository.findByChipId(chipId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        device.setDeviceKey(generate());
        deviceRepository.save(device);
        return device.getDeviceKey();
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(GROUPS * (GROUP_LEN + 1) - 1);
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < GROUP_LEN; i++) {
                sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
        }
        return sb.toString();
    }

    /**
     * Привязать устройство к аккаунту по паре «ID + ключ» с комплектного листа.
     * Ошибка одна и та же для неверного ID и неверного ключа — иначе можно было бы
     * перебором выяснить, какие ID вообще существуют.
     */
    @Transactional
    public Device claim(Long chipId, String key, Long userId) {
        if (chipId == null || key == null || key.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Device ID and key are required");
        }
        Device device = deviceRepository.findByChipId(chipId).orElse(null);
        if (device == null || !matches(device, key)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Wrong device ID or key");
        }
        appUserOrThrow(userId);
        Optional<DeviceAccess> existing = accessRepository.findByUserId(userId).stream()
                .filter(a -> chipId.equals(a.getChipId()))
                .findFirst();
        if (existing.isPresent()) {
            // Уже привязано: подтверждаем ключом, но права не расширяем — привязка
            // с ключом даёт ровно те права, что и приглашение от владельца: USER.
            existing.get().setAccessRole(DeviceAccessService.ACCESS_USER);
            accessRepository.save(existing.get());
        } else {
            DeviceAccess access = new DeviceAccess();
            access.setUserId(userId);
            access.setChipId(chipId);
            access.setAccessRole(DeviceAccessService.ACCESS_USER);
            accessRepository.save(access);
        }
        // Отсчёт оплаты блока начинается с ПЕРВОГО подтверждения ключом. Повторные
        // подтверждения (новый аккаунт с тем же листом) срок не сдвигают — иначе оплату
        // можно было бы продлевать, заводя новые аккаунты.
        payments.startOnRegistration(chipId, userId);
        return device;
    }

    public boolean matches(Device device, String key) {
        if (device == null || device.getDeviceKey() == null) {
            return false;
        }
        return normalize(device.getDeviceKey()).equals(normalize(key));
    }

    /** Убирает пробелы и дефисы, переводит в верхний регистр: ключ вводят как угодно. */
    static String normalize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (char c : key.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private ru.sensoterra.server.entity.AppUser appUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /** Чипы, к которым у аккаунта есть доступ — используется проверкой перед выдачей ключа. */
    public List<Long> chipsOf(Long userId) {
        return accessRepository.findByUserId(userId).stream().map(DeviceAccess::getChipId).toList();
    }
}