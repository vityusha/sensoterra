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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приглашения: ссылка одноразовая, живёт не дольше недели, работает и для нового
 * аккаунта (регистрация по ссылке), и для уже существующего.
 */
@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Приглашения на доступ к устройству")
class InviteTest {

    @Inject
    @Client("/")
    HttpClient client;

    private String adminToken;
    private String ownerToken;
    private String viewerToken;
    private Long blockId;
    private Long individualId;

    private String tokenFor(String username) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return (String) resp.body().get("token");
    }

    private void ensureAdmin() {
        if (adminToken != null) {
            return;
        }
        Map<String, String> creds = Map.of("username", "admin", "password", "admin-pass-123");
        try {
            adminToken = (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        } catch (HttpClientResponseException e) {
            adminToken = (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        }
    }

    /** Покупатель заводит аккаунт сам; админ создаёт только админов. */
    private Long ensureUser(String username) {
        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        for (Map u : list.body()) {
            if (username.equals(u.get("username"))) {
                return ((Number) u.get("id")).longValue();
            }
        }
        HttpResponse<Map> created = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return ((Number) created.body().get("id")).longValue();
    }

    private void assign(Long userId, Map<String, Object> request) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + userId + "/devices", request)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @BeforeEach
    void setUp() {
        ensureAdmin();
        Long ownerId = ensureUser("inv-owner");
        Long viewerId = ensureUser("inv-viewer");
        blockId = 960000L + Math.abs((long) java.util.UUID.randomUUID().getLeastSignificantBits() % 100000);
        individualId = 970000L + Math.abs((long) java.util.UUID.randomUUID().getLeastSignificantBits() % 100000);
        assign(ownerId, Map.of("chipId", blockId, "deviceType", "BLOCK"));
        assign(ownerId, Map.of("chipId", individualId, "deviceType", "INDIVIDUAL_SENSOR"));
        // Наблюдатель: аккаунт обычный, но на этом блоке у него права «только чтение».
        assign(viewerId, Map.of("chipId", blockId, "deviceType", "BLOCK", "accessRole", "VIEWER"));
        // План без регистрации: срок не идёт, но блок оплачен, поэтому приглашённый
        // видит его данные. Сам запуск отсчёта проверяет отдельный тест.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment", Map.of("unlimited", true))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        ownerToken = tokenFor("inv-owner");
        viewerToken = tokenFor("inv-viewer");
    }

    /** Состояние оплаты блока: приглашение не должно запускать отсчёт. */
    private Map blockPayment() {
        return client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/payment")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class)).body();
    }

    private List<Map> listInvites() {
        return client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/invites")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.listOf(Map.class)).body();
    }

    private List<Map> listIndividualInvites() {
        return client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + individualId + "/invites")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.listOf(Map.class)).body();
    }

    private Map clearFinished() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + blockId + "/invites/finished")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        return resp.body();
    }

    private Map createInvite() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/invites", Map.of())
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        return resp.body();
    }

    /** Сссылка на автономный датчик — бессрочная (expiresAt пустой). */
    private Map createIndividualInvite() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + individualId + "/invites", Map.of())
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        return resp.body();
    }

    @Test
    @Order(1)
    @DisplayName("Управляющий выдаёт ссылку, она живёт 7 дней")
    void ownerIssuesInvite() {
        Map invite = createInvite();
        assertNotNull(invite.get("token"));
        assertTrue(((String) invite.get("token")).matches("[A-Z2-9]{32}"), "токен без дефисов, URL-безопасен");
        assertEquals(blockId, ((Number) invite.get("chipId")).longValue());
        assertEquals(Boolean.TRUE, invite.get("usable"));

        long days = java.time.Duration.between(java.time.Instant.now(),
                java.time.Instant.parse(invite.get("expiresAt").toString())).toDays();
        assertTrue(days >= 6 && days <= 7, "срок недели, получено " + days);
    }

    @Test
    @Order(2)
    @DisplayName("Наблюдатель (VIEWER на устройстве) не может выдать приглашение")
    void viewerCannotInvite() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/" + blockId + "/invites", Map.of())
                                .header("Authorization", "Bearer " + viewerToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());
    }

    @Test
    @Order(3)
    @DisplayName("Приглашение до первой регистрации выдать можно: доступ ≠ оплата")
    void inviteWorksWithoutRegistration() {
        // Отсчёт блока ещё не запущен (ключ не подтверждён) — выдать ссылку можно,
        // принявший её управлять не сможет, пока не подтвердит блок ключом.
        assertEquals(Boolean.FALSE, blockPayment().get("started"));
        assertNotNull(createInvite().get("token"), "выдать приглашение должно быть можно без ключа");
    }

    @Test
    @Order(4)
    @DisplayName("Ссылка видна анонимно и показывает устройство")
    void previewIsAnonymous() {
        String token = (String) createInvite().get("token");

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/invites/" + token), Argument.of(Map.class));
        assertEquals(blockId, ((Number) resp.body().get("chipId")).longValue());
        assertEquals("BLOCK", resp.body().get("deviceType"));
        assertEquals("inv-owner", resp.body().get("invitedBy"));
        assertEquals(Boolean.TRUE, resp.body().get("usable"));
    }

    @Test
    @Order(5)
    @DisplayName("Существующий аккаунт принимает приглашение и получает доступ")
    void existingUserAccepts() {
        String token = (String) createInvite().get("token");
        Long guestId = ensureUser("inv-guest");

        HttpResponse<?> accepted = client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                        .header("Authorization", "Bearer " + tokenFor("inv-guest"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(204, accepted.getStatus().getCode());

        HttpResponse<List<Map>> devices = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + tokenFor("inv-guest")),
                Argument.listOf(Map.class));
        assertTrue(devices.body().stream()
                        .anyMatch(d -> ((Number) d.get("chipId")).longValue() == blockId),
                "после принятия устройство видно");
        assertNotNull(guestId);
    }

    @Test
    @Order(6)
    @DisplayName("Ссылка одноразовая")
    void inviteIsSingleUse() {
        String token = (String) createInvite().get("token");
        ensureUser("inv-guest2");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                        .header("Authorization", "Bearer " + tokenFor("inv-guest2"))
                        .contentType(MediaType.APPLICATION_JSON));

        Long strangerId = ensureUser("inv-stranger");
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                                .header("Authorization", "Bearer " + tokenFor("inv-stranger"))
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(410, ex.getStatus().getCode());
        assertNotNull(strangerId);

        // и предпросмотр тоже перестаёт работать
        assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/api/invites/" + token),
                        Argument.of(Map.class)));
    }

    @Test
    @Order(7)
    @DisplayName("Регистрация по ссылке: аккаунт создаётся и сразу получает доступ")
    void registerByInvite() {
        String token = (String) createInvite().get("token");
        String username = "inv-new-" + (System.nanoTime() % 100000);

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/register",
                                Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));

        assertNotNull(resp.body().get("token"), "сразу выдаём JWT, чтобы не заставлять логиниться");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) resp.body().get("user");
        assertEquals("USER", user.get("role"));
        assertFalse(user.containsKey("featuresEnabled"), "оплата — у блока, у аккаунта поля нет");

        String newToken = (String) resp.body().get("token");
        HttpResponse<List<Map>> devices = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + newToken),
                Argument.listOf(Map.class));
        assertTrue(devices.body().stream()
                        .anyMatch(d -> ((Number) d.get("chipId")).longValue() == blockId),
                "доступ выдан сразу после регистрации");
    }

    @Test
    @Order(8)
    @DisplayName("Битая ссылка не создаёт аккаунт")
    void badInviteDoesNotRegister() {
        String username = "inv-bad-" + (System.nanoTime() % 100000);
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/invites/ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ/register",
                                        Map.of("username", username, "password", "pw-123456"))
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(410, ex.getStatus().getCode());

        HttpResponse<List<Map>> users = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertFalse(users.body().stream().anyMatch(u -> username.equals(u.get("username"))),
                "аккаунт не должен появиться");
    }

    @Test
    @Order(9)
    @DisplayName("Отозванная ссылка не принимается и пропала из списка активных")
    void revokedInviteRejected() {
        Map invite = createInvite();
        String token = (String) invite.get("token");
        Long id = ((Number) invite.get("id")).longValue();

        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + blockId + "/invites/" + id)
                        .header("Authorization", "Bearer " + ownerToken));

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/api/invites/" + token),
                        Argument.of(Map.class)));
        assertEquals(410, ex.getStatus().getCode());

        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/invites")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.listOf(Map.class));
        Map revoked = list.body().stream()
                .filter(i -> token.equals(i.get("token")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, revoked.get("usable"));
        assertNotNull(revoked.get("id"));
    }

    @Test
    @Order(10)
    @DisplayName("Приглашение может выдать доступ «только чтение»")
    void inviteCanGrantReadOnlyAccess() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/invites",
                                Map.of("accessRole", "VIEWER"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals("VIEWER", resp.body().get("accessRole"));
        String token = (String) resp.body().get("token");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                        .header("Authorization", "Bearer " + tokenFor("inv-guest2"))
                        .contentType(MediaType.APPLICATION_JSON));

        String guestToken = tokenFor("inv-guest2");
        // Видит блок...
        client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/relays")
                        .header("Authorization", "Bearer " + guestToken),
                Argument.listOf(Map.class));
        // ...но управлять не может.
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/" + blockId + "/access",
                                Map.of("userId", viewerId2(), "accessRole", "VIEWER"))
                                .header("Authorization", "Bearer " + guestToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());
    }

    private Long viewerId2() {
        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        return ((Number) list.body().stream()
                .filter(u -> "inv-guest2".equals(u.get("username")))
                .findFirst().orElseThrow().get("id")).longValue();
    }

    @Test
    @Order(11)
    @DisplayName("Пригласивший не может принять собственную ссылку")
    void creatorCannotAcceptOwnInvite() {
        String token = (String) createInvite().get("token");
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(400, ex.getStatus().getCode());
    }

    @Test
    @Order(12)
    @DisplayName("Админ не рассылает приглашения — он выдаёт доступ напрямую")
    void adminDoesNotSendInvites() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/" + blockId + "/invites", Map.of())
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());
    }

    @Test
    @Order(13)
    @DisplayName("Чужой пользователь не видит приглашения устройства")
    void invitesAreDeviceScoped() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/devices/" + blockId + "/invites")
                                .header("Authorization", "Bearer " + tokenFor("inv-viewer"))));
        // Наблюдатель приглашения выдавать не может — на этом блоке у него только чтение.
        assertEquals(403, ex.getStatus().getCode());
    }
    @Test
    @DisplayName("Очистка убирает отозванные и истёкшие, но не трогает живые и принятые")
    void clearFinishedKeepsLiveAndAccepted() {
        String liveToken = (String) createInvite().get("token");
        String revokedToken = (String) createInvite().get("token");
        String acceptedToken = (String) createInvite().get("token");
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + blockId + "/invites/"
                                + inviteIdByToken(revokedToken))
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        Long guest = ensureUser("inv-cleaner-guest");
        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + acceptedToken + "/accept", Map.of())
                        .header("Authorization", "Bearer " + tokenFor("inv-cleaner-guest"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertNotNull(guest);

        assertEquals(1, ((Number) clearFinished().get("removed")).intValue(),
                "удалена ровно одна отозванная ссылка");

        List<String> left = listInvites().stream()
                .map(i -> (String) i.get("token"))
                .toList();
        assertTrue(left.contains(liveToken), "живая ссылка должна остаться");
        assertFalse(left.contains(revokedToken), "отозванная ссылка должна исчезнуть");
        assertTrue(left.contains(acceptedToken), "принятая ссылка объясняет, кому выдали доступ, и остаётся");
    }

    @Test
    @DisplayName("Очистка приглашений доступна только тому, кто управляет устройством")
    void clearFinishedNeedsShare() {
        createInvite();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.DELETE("/api/devices/" + blockId + "/invites/finished")
                                .header("Authorization", "Bearer " + viewerToken),
                        Argument.of(Map.class)));
        assertEquals(403, ex.getStatus().getCode());
    }

    private Map clearAll() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + blockId + "/invites/all")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        return resp.body();
    }

    @Test
    @DisplayName("Полная очистка убирает и принятые, и живые ссылки")
    void clearAllRemovesEverything() {
        createInvite();
        String acceptedToken = (String) createInvite().get("token");
        ensureUser("inv-wipe-guest");
        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + acceptedToken + "/accept", Map.of())
                        .header("Authorization", "Bearer " + tokenFor("inv-wipe-guest"))
                        .contentType(MediaType.APPLICATION_JSON));

        assertEquals(2, ((Number) clearAll().get("removed")).intValue());
        assertTrue(listInvites().isEmpty(), "после полной очистки список пуст");
    }

    @Test
    @DisplayName("Полная очистка требует права управлять устройством")
    void clearAllNeedsShare() {
        createInvite();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.DELETE("/api/devices/" + blockId + "/invites/all")
                                .header("Authorization", "Bearer " + viewerToken),
                        Argument.of(Map.class)));
        assertEquals(403, ex.getStatus().getCode());
    }

    private long inviteIdByToken(String token) {
        return listInvites().stream()
                .filter(i -> token.equals(i.get("token")))
                .map(i -> ((Number) i.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }

    @Test
    @Order(70)
    @DisplayName("Автономный датчик: ссылка без срока действия, но пригодна к приёму")
    void individualInviteHasNoExpiry() {
        Map invite = createIndividualInvite();
        assertNotNull(invite.get("token"));
        assertEquals(individualId, ((Number) invite.get("chipId")).longValue());
        assertNull(invite.get("expiresAt"), "у автономного датчика срок отключения не нужен");
        assertEquals(Boolean.TRUE, invite.get("usable"));

        HttpResponse<Map> preview = client.toBlocking().exchange(
                HttpRequest.GET("/api/invites/" + invite.get("token")),
                Argument.of(Map.class));
        assertEquals(200, preview.getStatus().getCode());
        assertEquals(Boolean.TRUE, preview.body().get("usable"));
        assertNull(preview.body().get("expiresAt"));
    }

    @Test
    @Order(71)
    @DisplayName("Бессрочная ссылка автономного датчика принимается и даёт доступ")
    void eternalInviteAccepts() {
        Map invite = createIndividualInvite();
        String token = (String) invite.get("token");
        Long guestId = ensureUser("inv-ind-guest");
        String guestToken = tokenFor("inv-ind-guest");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/invites/" + token + "/accept", Map.of())
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON));

        HttpResponse<List<Map>> accessResp = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + individualId + "/access")
                        .header("Authorization", "Bearer " + guestToken),
                Argument.listOf(Map.class));
        assertTrue(accessResp.body().stream()
                        .anyMatch(a -> guestId.equals(((Number) a.get("userId")).longValue())),
                "гость получил доступ к автономному датчику");
    }

    @Test
    @Order(72)
    @DisplayName("Очистка завершённых не трогает бессрочную ссылку")
    void clearFinishedKeepsEternalInvite() {
        String token = (String) createIndividualInvite().get("token");
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/devices/" + individualId + "/invites/finished")
                        .header("Authorization", "Bearer " + ownerToken),
                Argument.of(Map.class));
        assertEquals(0, ((Number) resp.body().get("removed")).intValue());
        assertTrue(listIndividualInvites().stream()
                .anyMatch(i -> token.equals(i.get("token")) && Boolean.TRUE.equals(i.get("usable"))),
                "бессрочная ссылка осталась живой");
    }

}