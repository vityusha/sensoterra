package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.Relay;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorRegistration;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.repository.RelayRepository;
import ru.sensoterra.server.repository.SensorRegistrationRepository;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.BlockPaymentService;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.InviteService;
import ru.sensoterra.server.service.DeviceDeletionService;
import ru.sensoterra.server.service.DeviceKeyService;
import ru.sensoterra.server.service.DeviceStateStore;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Controller("/api/devices")
@Singleton
public class DeviceController {

    private final DeviceRepository deviceRepository;
    private final RelayRepository relayRepository;
    private final SensorRepository sensorRepository;
    private final SensorRegistrationRepository sensorRegistrationRepository;
    private final DeviceStateStore stateStore;
    private final DeviceDeletionService deviceDeletionService;
    private final DeviceAccessService access;
    private final DeviceKeyService deviceKeys;
    private final BlockPaymentService payments;
    private final InviteService invites;
    private final MqttClientService mqttClientService;

    public DeviceController(DeviceRepository deviceRepository,
                            RelayRepository relayRepository,
                            SensorRepository sensorRepository,
                            SensorRegistrationRepository sensorRegistrationRepository,
                            DeviceStateStore stateStore,
                            DeviceDeletionService deviceDeletionService,
                            DeviceAccessService access,
                            DeviceKeyService deviceKeys,
                            BlockPaymentService payments,
                            InviteService invites,
                            MqttClientService mqttClientService) {
        this.deviceRepository = deviceRepository;
        this.relayRepository = relayRepository;
        this.sensorRepository = sensorRepository;
        this.sensorRegistrationRepository = sensorRegistrationRepository;
        this.stateStore = stateStore;
        this.deviceDeletionService = deviceDeletionService;
        this.access = access;
        this.deviceKeys = deviceKeys;
        this.payments = payments;
        this.invites = invites;
        this.mqttClientService = mqttClientService;
    }

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<Device> list() {
        return visibleDevices();
    }

    @Get("/status")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<DeviceStatusInfo> statuses() {
        return visibleDevices().stream()
                .map(d -> new DeviceStatusInfo(
                        d.getChipId(),
                        stateStore.getStatus(d.getChipId()),
                        stateStore.getLastSeen(d.getChipId())))
                .toList();
    }

    @Get("/{chipId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public Device get(Long chipId) {
        access.requireView(chipId);
        return access.device(chipId);
    }

    /**
     * Состояние оплаты блока. Проверяется только право видеть устройство: страница блока
     * должна показать «оплата истекла» и остаток, а не молчаливую ошибку 402.
     */
    @Get("/{chipId}/payment")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public BlockPaymentService.PaymentInfo payment(Long chipId) {
        if (!access.canView(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        return payments.info(chipId);
    }

    /** Оплата по всем видимым устройствам — чтобы список знал, где платить. */
    @Get("/payment")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<BlockPaymentService.PaymentInfo> paymentsOverview() {
        return visibleDevices().stream().map(d -> payments.info(d.getChipId())).toList();
    }

    /**
     * Назначить блоку срок или бессрочный доступ. days = null берёт дефолт из настроек,
     * unlimited = true — навсегда. Админ настраивает конкретный блок, ничего не трогая в базе.
     */
    @Post("/{chipId}/payment")
    @Secured("ADMIN")
    public BlockPaymentService.PaymentInfo setPayment(Long chipId, @Body PaymentPlanRequest request) {
        access.requireAdmin();
        return payments.setPlan(chipId, request.days(), request.unlimited());
    }

    /** Продление: дни добавляются к текущему концу срока, истёкший отсчёт идёт заново. */
    @Post("/{chipId}/payment/extend")
    @Secured("ADMIN")
    public BlockPaymentService.PaymentInfo extendPayment(Long chipId, @Body PaymentExtendRequest request) {
        access.requireAdmin();
        return payments.extend(chipId, request.days());
    }

    /**
     * Отменить все регистрации блока: снимаем доступы, отзываем живые ссылки и обнуляем
     * отсчёт. Следующий, кто подтвердит блок ключом, начнёт срок заново по тому же плану.
     */
    @Post("/{chipId}/payment/reset")
    @Secured("ADMIN")
    public HttpResponse<?> resetRegistrations(Long chipId) {
        access.requireAdmin();
        if (!deviceRepository.existsById(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        access.revokeAllForDevice(chipId);
        int revokedInvites = invites.revokeAll(chipId);
        payments.resetClock(chipId);
        return HttpResponse.ok(new RegistrationResetResult(chipId, revokedInvites));
    }

    /**
     * Снять retained с настроек устройства (Sleep_Send, PARAM_*). Нужно, когда retained
     * пережил применение: настройки, заданные вручную, сменились, а при следующем
     * пробуждении датчик снова применил старые.
     */
    @Post("/{chipId}/retained/clear")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public MqttClientService.RetainedClearResult clearRetainedSettings(Long chipId) {
        access.requireControl(chipId);
        if (!deviceRepository.existsById(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        if (!mqttClientService.isConnected()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT broker not connected");
        }
        return mqttClientService.clearRetainedSettings(chipId);
    }

    @Get("/{chipId}/relays")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<Relay> relays(Long chipId) {
        access.requireView(chipId);
        return relayRepository.findByDeviceIdOrderByRelayNumberAsc(chipId);
    }

    @Get("/{chipId}/sensors")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<Sensor> sensors(Long chipId) {
        access.requireView(chipId);
        return sensorRepository.findByDeviceId(chipId);
    }

    @Get("/{chipId}/registered-sensors")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<SensorRegistration> registeredSensors(Long chipId) {
        access.requireView(chipId);
        return sensorRegistrationRepository.findByDeviceIdAndSensorChipIdIsNotNull(chipId);
    }

    /**
     * Привязка своего блока по паре «ID + ключ» с комплектного листа. Так покупатель
     * подтверждает владение и получает права управления; повторный вызов ничего не ломает.
     */
    @Post("/claim")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<ClaimResult> claim(@Body ClaimRequest request) {
        Long userId = access.currentUser().getId();
        Device device = deviceKeys.claim(request.chipId(), request.key(), userId);
        return HttpResponse.ok(new ClaimResult(device.getChipId(), device.getDeviceType(),
                access.accessRoleOf(userId, device.getChipId())));
    }

    /** Аккаунты, которым выдан доступ к этому устройству, и их права на него. */
    @Get("/{chipId}/access")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<AccessEntry> accessEntries(Long chipId) {
        access.requireView(chipId);
        return access.accountsWithAccess(chipId).stream()
                .map(u -> new AccessEntry(u.getId(), u.getUsername(), u.getRole(),
                        access.accessRoleOf(u.getId(), chipId)))
                .toList();
    }

    /** Выдать доступ или сменить права: accessRole = USER (управление) либо VIEWER (чтение). */
    @Post("/{chipId}/access")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<AccessEntry> grantAccess(Long chipId, @Body AccessGrantRequest request) {
        access.requireManageAccess(chipId);
        var user = access.byId(request.userId());
        if (DeviceAccessService.ACCESS_OWNER.equals(access.accessRoleOf(user.getId(), chipId))) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "The owner's access can't be changed");
        }
        access.grant(user.getId(), chipId, request.accessRole());
        return HttpResponse.ok(new AccessEntry(user.getId(), user.getUsername(), user.getRole(),
                access.accessRoleOf(user.getId(), chipId)));
    }

    @Delete("/{chipId}/access/{userId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> revokeAccess(Long chipId, Long userId) {
        boolean self = userId.equals(access.currentUser().getId());
        if (self && access.canView(chipId)) {
            // Any guest may leave a device; the owner can't (it would orphan the device).
            if (access.isOwner(chipId)) {
                throw new HttpStatusException(HttpStatus.CONFLICT, "The owner can't remove own access");
            }
        } else {
            access.requireManageAccess(chipId);
            if (DeviceAccessService.ACCESS_OWNER.equals(access.accessRoleOf(userId, chipId)) && !access.isAdmin()) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Only an admin can remove the owner");
            }
        }
        access.revoke(userId, chipId);
        return HttpResponse.noContent();
    }

    /**
     * Ключ устройства для комплектного листа. Только админ: он печатает лист и отдаёт
     * покупателю вместе с блоком.
     */
    @Get("/{chipId}/key")
    @Secured("ADMIN")
    public HttpResponse<DeviceKeyResponse> deviceKey(Long chipId) {
        access.requireAdmin();
        if (!deviceRepository.existsById(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        return HttpResponse.ok(new DeviceKeyResponse(chipId, deviceKeys.keyOf(chipId)));
    }

    /** Перевыпуск ключа: прежний лист перестаёт работать, выданные доступы остаются. */
    @Post("/{chipId}/key/regenerate")
    @Secured("ADMIN")
    public HttpResponse<DeviceKeyResponse> regenerateDeviceKey(Long chipId) {
        access.requireAdmin();
        return HttpResponse.ok(new DeviceKeyResponse(chipId, deviceKeys.regenerate(chipId)));
    }

    @Delete("/{chipId}")
    @Secured("ADMIN")
    public HttpResponse<DeviceDeletionService.DeletionResult> delete(Long chipId) {
        if (!deviceRepository.existsById(chipId)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        return HttpResponse.ok(deviceDeletionService.deleteDevice(chipId));
    }

    private List<Device> visibleDevices() {
        Set<Long> ids = access.visibleChipIds();
        return deviceRepository.findAll().stream()
                .filter(d -> ids.contains(d.getChipId()))
                .toList();
    }

    @Serdeable
    public record DeviceStatusInfo(Long chipId, String status, Instant lastSeen) {
    }

    @Serdeable
    public record AccessEntry(Long userId, String username, String role, String accessRole) {
    }

    @Serdeable
    public record AccessGrantRequest(Long userId, String accessRole) {
    }

    @Serdeable
    public record ClaimRequest(Long chipId, String key) {
    }

    @Serdeable
    public record ClaimResult(Long chipId, String deviceType, String accessRole) {
    }

    @Serdeable
    public record DeviceKeyResponse(Long chipId, String key) {
    }

    @Serdeable
    public record PaymentPlanRequest(Integer days, Boolean unlimited) {
    }

    @Serdeable
    public record PaymentExtendRequest(Integer days) {
    }

    /** Сколько живых ссылок отозвано вместе с доступами. */
    @Serdeable
    public record RegistrationResetResult(Long chipId, int revokedInvites) {
    }
}
