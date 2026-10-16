package ru.sensoterra.server.controller;

import ru.sensoterra.server.service.BlockPaymentService;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.micronaut.security.annotation.Secured;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Настройки оплаты, которые админ меняет из интерфейса.
 *
 * Раньше срок задавался правкой базы, и блок без ручной настройки просто не тарифицировался.
 * Теперь дефолт задаётся здесь и применяется ко всем блокам без собственного плана, а
 * конкретному блоку срок назначается в его карточке.
 */
@Controller("/api/admin/settings")
@Secured("ADMIN")
public class AdminSettingsController {

    private final BlockPaymentService payments;

    public AdminSettingsController(BlockPaymentService payments) {
        this.payments = payments;
    }

    @Get("/block-payment")
    public BlockPaymentService.Defaults blockPayment() {
        return payments.defaults();
    }

    @Put("/block-payment")
    public BlockPaymentService.Defaults setBlockPayment(@Body BlockPaymentRequest request) {
        return payments.setDefaults(request.days(), request.unlimited());
    }

    @Serdeable
    public record BlockPaymentRequest(int days, boolean unlimited) {
    }
}
