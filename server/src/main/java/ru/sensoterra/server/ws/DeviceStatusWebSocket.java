package ru.sensoterra.server.ws;

import ru.sensoterra.server.service.DeviceAccessService;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.validator.TokenValidator;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Рассылка статусов. На уровне Micronaut endpoint анонимный (браузер не умеет слать
 * заголовок Authorization в WebSocket), поэтому токен проверяется вручную при открытии:
 * query-параметр ?token=... либо заголовок Authorization. Без валидного токена соединение
 * сразу закрывается, а рассылка фильтруется по устройствам, доступным аккаунту.
 */
@ServerWebSocket("/ws/status")
@Secured(SecurityRule.IS_ANONYMOUS)
public class DeviceStatusWebSocket {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusWebSocket.class);

    /** Как долго кэшируется список доступных устройств сессии. */
    private static final long VISIBLE_TTL_MS = 10_000;

    private static final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, SessionInfo> infos = new ConcurrentHashMap<>();

    private final ExecutorService validationExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ws-token-validator");
                t.setDaemon(true);
                return t;
            });

    private final TokenValidator<HttpRequest<?>> tokenValidator;
    private final DeviceAccessService access;
    private final DeviceStatusWebSocketBroadcaster broadcaster;

    public DeviceStatusWebSocket(TokenValidator<HttpRequest<?>> tokenValidator,
                                 DeviceAccessService access,
                                 DeviceStatusWebSocketBroadcaster broadcaster) {
        this.tokenValidator = tokenValidator;
        this.access = access;
        this.broadcaster = broadcaster;
    }

    @OnOpen
    public void onOpen(HttpRequest<?> request, WebSocketSession session) {
        String token = extractToken(request, session);
        if (token == null) {
            log.warn("WebSocket rejected (no token): {}", session.getId());
            session.close(CloseReason.POLICY_VIOLATION);
            return;
        }
        // Валидатор отдаёт Publisher, а onOpen идёт на event-loop, где block() запрещён,
        // поэтому проверка уходит на блочный пул и завершает открытие асинхронно.
        Mono.from(tokenValidator.validateToken(token, request))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("invalid token")))
                .publishOn(Schedulers.fromExecutorService(validationExecutor))
                .subscribe((Authentication auth) -> onAuthenticated(session, auth),
                        e -> {
                            log.warn("WebSocket rejected (bad token): {} tokenPrefix={}", session.getId(),
                    token.substring(0, Math.min(8, token.length())));
                            session.close(CloseReason.POLICY_VIOLATION);
                        });
    }

    private void onAuthenticated(WebSocketSession session, Authentication auth) {
        if (!session.isOpen()) {
            return;
        }
        String username = auth.getName();
        SessionInfo info = new SessionInfo(username, access.isAdmin(username),
                () -> access.visibleChipIdsFor(username), () -> access.accountActive(username));
        info.refresh();
        infos.put(session.getId(), info);
        sessions.add(session);
        log.debug("WebSocket opened: {} user={} admin={} devices={}",
                session.getId(), username, info.admin, info.visibleCount());
        broadcaster.broadcastCurrentStatus(session);
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        log.debug("WebSocket message from {}: {}", session.getId(), message);
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        sessions.remove(session);
        infos.remove(session.getId());
        log.debug("WebSocket closed: {}", session.getId());
    }

    /**
     * Браузер не умеет слать заголовки в WebSocket, поэтому токен приходит в query-строке.
     * Пробуем сначала запрос, потом параметры сессии, потом сам URI — в зависимости от
     * транспорта доступен только один из источников.
     */
    private String extractToken(HttpRequest<?> request, WebSocketSession session) {
        if (request != null && request.getParameters().get("token") != null) {
            return request.getParameters().get("token");
        }
        String fromSession = session.getRequestParameters().get("token");
        if (fromSession != null && !fromSession.isBlank()) {
            return fromSession;
        }
        String uri = session.getRequestURI().getRawQuery();
        if (uri == null || uri.isBlank()) {
            return null;
        }
        for (String pair : uri.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    public static Set<WebSocketSession> getSessions() {
        return sessions;
    }

    public static SessionInfo info(WebSocketSession session) {
        return infos.get(session.getId());
    }

    /** Аккаунт WS-сессии и кэш доступных ему устройств. */
    public static final class SessionInfo {

        private final String username;
        private final boolean admin;
        private final Supplier<Set<Long>> loader;
        private final java.util.function.BooleanSupplier active;
        private volatile Set<Long> chips = Set.of();
        private volatile long expiresAt = 0;

        SessionInfo(String username, boolean admin, Supplier<Set<Long>> loader,
                    java.util.function.BooleanSupplier active) {
            this.username = username;
            this.admin = admin;
            this.loader = loader;
            this.active = active;
        }

        public void refresh() {
            this.chips = loader.get();
            this.expiresAt = System.currentTimeMillis() + VISIBLE_TTL_MS;
        }

        public boolean isAdmin() {
            return admin;
        }

        public String username() {
            return username;
        }

        public int visibleCount() {
            return chips.size();
        }

        /**
         * Видно ли событие сессии. Админу всё; остальным — только если все упомянутые
         * в сообщении устройства им доступны.
         */
        public boolean canSee(Set<Long> chipsToCheck) {
            // Отключение аккаунта действует на живую сессию сразу, без разрыва WS.
            if (!active.getAsBoolean()) {
                return false;
            }
            if (admin || chipsToCheck == null || chipsToCheck.isEmpty()) {
                return true;
            }
            if (System.currentTimeMillis() > expiresAt) {
                refresh();
            }
            return chips.containsAll(chipsToCheck);
        }
    }
}
