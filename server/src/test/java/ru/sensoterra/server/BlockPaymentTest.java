package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micronaut.data.connection.annotation.Connectable;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Оплата принадлежит блоку, а не аккаунту: срок идёт с первого подтверждения ключом,
 * общий для всех, кто имеет доступ, и новый аккаунт с тем же листом его не обнуляет.
 *
 * Платёжного контура пока нет — план назначает админ, но уже из интерфейса: срок по
 * умолчанию для всех блоков задаётся в настройках, конкретному блоку — в его карточке.
 */
@MicronautTest(transactional = false)
@DisplayName("Оплата блока: срок с первой регистрации, общий для всех")
class BlockPaymentTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    DataSource dataSource;

    private static final String BUYER = "pay-buyer";
    private static final String SECOND = "pay-second";
    private static final String ADMIN = "admin";

    private String adminToken;
    private Long buyerId;
    private Long secondId;
    private Long blockId;
    private Long sensorId;

    // --- Обвязка теста ---

    @BeforeEach
    void setUp() {
        adminToken = loginOrBootstrap();
        buyerId = ensureUser(BUYER);
        secondId = ensureUser(SECOND);
        long seed = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);
        blockId = 770000L + seed;
        sensorId = 960000L + seed;
        assign(blockId, "BLOCK");
        assign(sensorId, "INDIVIDUAL_SENSOR");
        // Чистый блок на каждый прогон: своего плана нет, значит действует дефолт.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment",
                                Map.of("unlimited", false))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        revokeAll();
        // Сброс снимает и выданный админом доступ, а тесту он нужен: закрытый блок должен
        // быть виден своему пользователю, иначе нечем показывать, что он закрыт.
        assign(blockId, "BLOCK");
        grantDefault(365, false);
    }

    private String loginOrBootstrap() {
        Map<String, String> creds = Map.of("username", ADMIN, "password", "admin123");
        try {
            return (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        } catch (HttpClientResponseException e) {
            return (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        }
    }

    private String tokenFor(String username) {
        return (String) client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                                Map.of("username", username, "password", "pw12345"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("token");
    }

    private Long ensureUser(String username) {
        for (Map u : client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class)).body()) {
            if (username.equals(u.get("username"))) {
                return ((Number) u.get("id")).longValue();
            }
        }
        return ((Number) client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register", Map.of("username", username, "password", "pw12345"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("id")).longValue();
    }

    private void assign(Long chipId, String type) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + buyerId + "/devices",
                                Map.of("chipId", chipId, "deviceType", type))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private void revokeAll() {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment/reset", Map.of())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private String deviceKey(Long chipId) {
        return (String) client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + chipId + "/key")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class)).body().get("key");
    }

    private void grantDefault(int days, boolean unlimited) {
        client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/settings/block-payment",
                                Map.of("days", days, "unlimited", unlimited))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private void claim(Long chipId, Long userId) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/claim", Map.of("chipId", chipId, "key", deviceKey(chipId)))
                        .header("Authorization", "Bearer " + tokenFor(usernameOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private String usernameOf(Long userId) {
        return userId.equals(buyerId) ? BUYER : SECOND;
    }

    private Map payment(Long chipId, String token) {
        return client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + chipId + "/payment")
                        .header("Authorization", "Bearer " + token),
                Argument.of(Map.class)).body();
    }

    /** Управляющая операция: проходит через requireControl, то есть через оплату. */
    private int controlProbe(Long chipId, String token) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/devices/" + chipId + "/access", Map.of("userId", secondId))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private int readProbe(Long chipId, String token) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/devices/" + chipId + "/relays")
                            .header("Authorization", "Bearer " + token),
                    Argument.listOf(Map.class));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    // --- Сценарии ---

    @Test
    @DisplayName("До подтверждения ключом блок закрыт: ни управления, ни чтения")
    void unregisteredBlockIsClosed() {
        String token = tokenFor(BUYER);
        Map info = payment(blockId, token);
        assertEquals(Boolean.FALSE, info.get("paid"), "отсчёта ещё нет");
        assertEquals(Boolean.FALSE, info.get("started"), "блок не зарегистрирован");

        assertEquals(402, controlProbe(blockId, token));
        assertEquals(402, readProbe(blockId, token), "статистика и состояние тоже платные");
    }

    @Test
    @DisplayName("Первое подтверждение ключом запускает отсчёт по дефолту и открывает блок")
    void firstClaimStartsClockFromDefault() {
        String token = tokenFor(BUYER);
        claim(blockId, buyerId);

        Map info = payment(blockId, token);
        assertEquals(Boolean.TRUE, info.get("started"));
        assertEquals(Boolean.TRUE, info.get("paid"));
        assertNotNull(info.get("expiresAt"), "срок посчитан от регистрации");
        assertEquals(Boolean.FALSE, info.get("unlimited"), "дефолт — срочный");
        long days = ((Number) info.get("remainingSeconds")).longValue() / 86400L;
        assertTrue(days > 360 && days <= 365, "осталось около дефолтных 365 дней, а не " + days);

        assertEquals(200, controlProbe(blockId, token));
        assertEquals(200, readProbe(blockId, token));
    }

    @Test
    @DisplayName("Второй аккаунт с тем же листом не начинает срок заново")
    void secondClaimDoesNotRestartClock() {
        claim(blockId, buyerId);
        String first = String.valueOf(payment(blockId, tokenFor(BUYER)).get("expiresAt"));

        claim(blockId, secondId);

        Map info = payment(blockId, tokenFor(SECOND));
        assertEquals(first, info.get("expiresAt"), "срок общий на блок, не на аккаунт");
        assertEquals(200, readProbe(blockId, tokenFor(SECOND)),
                "пришедший по приглашению видит тот же остаток");
    }

    @Test
    @DisplayName("Свой план блока важнее дефолта")
    void blockPlanBeatsDefault() {
        setPlan(blockId, Map.of("days", 30));
        grantDefault(365, false);
        claim(blockId, buyerId);

        Map info = payment(blockId, tokenFor(BUYER));
        long days = ((Number) info.get("remainingSeconds")).longValue() / 86400L;
        assertTrue(days > 25 && days <= 30, "взялся срок блока (30), а не дефолт: " + days);
        assertEquals(30, ((Number) info.get("planDays")).intValue());
    }

    @Test
    @DisplayName("Бессрочный доступ: срока нет, остаток не считается")
    void unlimitedHasNoCountdown() {
        setPlan(blockId, Map.of("unlimited", true));
        claim(blockId, buyerId);

        Map info = payment(blockId, tokenFor(BUYER));
        assertEquals(Boolean.TRUE, info.get("unlimited"));
        assertEquals(Boolean.TRUE, info.get("paid"));
        assertNull(info.get("expiresAt"), "бессрочному блоку дата не нужна");
        assertNull(info.get("remainingSeconds"), "считать нечего");
        assertEquals(200, controlProbe(blockId, tokenFor(BUYER)));
    }

    @Test
    @DisplayName("Продление добавляет дни к текущему концу срока")
    void extendAddsToCurrentEnd() {
        setPlan(blockId, Map.of("days", 10));
        claim(blockId, buyerId);
        long before = ((Number) payment(blockId, tokenFor(BUYER)).get("remainingSeconds")).longValue();

        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment/extend", Map.of("days", 20))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));

        long after = ((Number) payment(blockId, tokenFor(BUYER)).get("remainingSeconds")).longValue();
        long addedDays = (after - before) / 86400L;
        assertTrue(addedDays >= 19 && addedDays <= 20, "добавлено 20 дней, а не от сегодня: " + addedDays);
    }

    @Test
    @DisplayName("Истёкший срок закрывает и управление, и статистику")
    void expiredClosesEverything() {
        // Срок в прошлом задаём планом на 1 день и сдвигаем отсчёт назад через прямое
        // обновление строки: проверять надо поведение гейта, а не способ выставить дату.
        setPlan(blockId, Map.of("days", 1));
        claim(blockId, buyerId);
        expireNow(blockId);

        String token = tokenFor(BUYER);
        Map info = payment(blockId, token);
        assertEquals(Boolean.FALSE, info.get("paid"));
        assertEquals(0L, ((Number) info.get("remainingSeconds")).longValue());
        assertEquals(402, controlProbe(blockId, token));
        assertEquals(402, readProbe(blockId, token));
    }

    @Test
    @DisplayName("Автономный датчик бесплатен: оплата блока на него не действует")
    void standaloneSensorIsFree() {
        claim(sensorId, buyerId);
        Map info = payment(sensorId, tokenFor(BUYER));
        assertEquals(Boolean.FALSE, info.get("chargeable"), "датчик не тарифицируется");
        assertEquals(Boolean.TRUE, info.get("paid"));
        assertEquals(200, controlProbe(sensorId, tokenFor(BUYER)));
        assertEquals(200, readProbe(sensorId, tokenFor(BUYER)));
    }

    @Test
    @DisplayName("Админ отменяет все регистрации блока: счётчик и доступы обнулены")
    void adminResetsRegistrations() {
        claim(blockId, buyerId);
        grant(blockId, secondId);
        assertEquals(200, readProbe(blockId, tokenFor(SECOND)));

        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment/reset", Map.of())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));

        Map info = payment(blockId, adminToken);
        assertEquals(Boolean.FALSE, info.get("started"), "отсчёт обнулён");
        assertNull(info.get("expiresAt"), "срок ждёт новой регистрации");
        assertEquals(404, readProbe(blockId, tokenFor(SECOND)), "доступ снят");
        assertEquals(404, readProbe(blockId, tokenFor(BUYER)));
    }

    @Test
    @DisplayName("После отмены регистраций отсчёт снова идёт с первого подтверждения ключом")
    void clockRestartsOnNextClaim() {
        setPlan(blockId, Map.of("days", 45));
        claim(blockId, buyerId);
        revokeAll();
        claim(blockId, secondId);

        Map info = payment(blockId, tokenFor(SECOND));
        assertEquals(Boolean.TRUE, info.get("started"));
        assertEquals(Boolean.TRUE, info.get("paid"));
        long days = ((Number) info.get("remainingSeconds")).longValue() / 86400L;
        assertTrue(days > 40 && days <= 45, "по плану блока, а не по старому сроку: " + days);
    }

    @Test
    @DisplayName("Удаление регистратора обнуляет отсчёт его блоков")
    void deletingRegistrarResetsBlockClock() {
        claim(blockId, buyerId);
        assertEquals(Boolean.TRUE, payment(blockId, adminToken).get("started"));

        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/admin/users/" + buyerId)
                        .header("Authorization", "Bearer " + adminToken));

        assertEquals(Boolean.FALSE, payment(blockId, adminToken).get("started"));
        assertNull(payment(blockId, adminToken).get("expiresAt"));
    }

    @Test
    @DisplayName("Пользователь не может ни назначить, ни продлить оплату")
    void userCannotTouchPayment() {
        assertEquals(403, post("/api/devices/" + blockId + "/payment", Map.of("days", 10), tokenFor(BUYER)));
        assertEquals(403, post("/api/devices/" + blockId + "/payment/extend", Map.of("days", 10),
                tokenFor(BUYER)));
        assertEquals(403, post("/api/devices/" + blockId + "/payment/reset", Map.of(), tokenFor(BUYER)));
        assertEquals(403, put("/api/admin/settings/block-payment", Map.of("days", 1, "unlimited", false),
                tokenFor(BUYER)));
    }

    @Test
    @DisplayName("Дефолт админ меняет из интерфейса, и он применяется к новым блокам")
    void adminChangesDefault() {
        grantDefault(30, false);
        Map defaults = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/settings/block-payment")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class)).body();
        assertEquals(30, ((Number) defaults.get("days")).intValue());
        assertEquals(Boolean.FALSE, defaults.get("unlimited"));

        claim(blockId, buyerId);
        long days = ((Number) payment(blockId, tokenFor(BUYER)).get("remainingSeconds")).longValue() / 86400L;
        assertTrue(days > 25 && days <= 30, "новый дефолт применён: " + days);
    }

    @Test
    @DisplayName("Дефолт «бессрочно» закрывает новые блоки без срока")
    void defaultCanBeUnlimited() {
        grantDefault(30, true);
        claim(blockId, buyerId);
        Map info = payment(blockId, tokenFor(BUYER));
        assertEquals(Boolean.TRUE, info.get("unlimited"));
        assertNull(info.get("expiresAt"));
        assertEquals(200, controlProbe(blockId, tokenFor(BUYER)));
    }

    @Test
    @DisplayName("В списке устройств видно, где оплата есть, а где нет")
    void listCarriesPaymentState() {
        claim(blockId, buyerId);

        List<Map> all = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/payment").header("Authorization", "Bearer " + tokenFor(BUYER)),
                Argument.listOf(Map.class)).body();
        Map block = all.stream().filter(p -> ((Number) p.get("chipId")).longValue() == blockId)
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, block.get("chargeable"));
        assertEquals(Boolean.TRUE, block.get("paid"));
    }

    // --- Вспомогательное ---

    private void setPlan(Long chipId, Map<String, Object> body) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + chipId + "/payment", body)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
    }

    private void grant(Long chipId, Long userId) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + chipId + "/access", Map.of("userId", userId))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    /**
     * Сдвигаем срок в прошлое прямо в строке оплаты: ждать истечения нельзя, а проверять
     * надо поведение гейта. Через JDBC, а не через репозиторий, — тест специально правит
     * то, чего интерфейс админа делать не позволяет.
     */
    @Connectable
    void expireNow(Long chipId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE block_entitlement SET expires_at = ? WHERE chip_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(3600)));
            ps.setLong(2, chipId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("не удалось выставить истёкший срок", e);
        }
    }

    private int post(String path, Map<String, Object> body, String token) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.POST(path, body)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private int put(String path, Map<String, Object> body, String token) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.PUT(path, body)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    @DisplayName("Срок не выдаётся раньше подтверждения ключом, даже если админ его продлил")
    void extendBeforeRegistrationIsStoredAsPlan() {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment/extend", Map.of("days", 100))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));

        Map before = payment(blockId, tokenFor(BUYER));
        assertEquals(Boolean.FALSE, before.get("started"));
        assertNull(before.get("expiresAt"), "до регистрации срока нет");

        claim(blockId, buyerId);
        long days = ((Number) payment(blockId, tokenFor(BUYER)).get("remainingSeconds")).longValue() / 86400L;
        assertTrue(days > 455 && days <= 466, "продление сложилось с дефолтом: " + days);
    }
}
