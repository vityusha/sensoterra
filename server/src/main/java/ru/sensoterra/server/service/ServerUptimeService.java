package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.ServerUptimeLog;
import ru.sensoterra.server.repository.ServerUptimeLogRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
@Context
public class ServerUptimeService {

    private static final Logger log = LoggerFactory.getLogger(ServerUptimeService.class);

    private final ServerUptimeLogRepository repository;
    private final ScheduledExecutorService scheduler;

    @Value("${datasources.default.url}")
    String jdbcUrl;

    @Value("${datasources.default.username}")
    String jdbcUser;

    @Value("${datasources.default.password}")
    String jdbcPassword;

    public ServerUptimeService(ServerUptimeLogRepository repository) {
        this.repository = repository;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "uptime-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void started() {
        try {
            // Аварийные/аппаратные выключения не дают graceful-shutdown: зависшие открытые
            // записи закрываем по последнему известному heartbeat.
            closeStaleOpen();
            ServerUptimeLog entry = new ServerUptimeLog();
            Instant now = Instant.now();
            entry.setStartedAt(now);
            entry.setLastSeen(now);
            repository.save(entry);
            log.info("Server uptime log opened at {}", entry.getStartedAt());
            scheduler.scheduleWithFixedDelay(this::heartbeat, 30, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to record server start in uptime log", e);
        }
    }

    /**
     * Закрытие текущей записи на shutdown. На завершении JVM пулы Hikari/Hibernate уже
     * остановлены (поэтому без @Transactional/repository), работаем через собственное
     * JDBC-соединение — оно не зависит от управляемых пулов.
     */
    @PreDestroy
    public void stopped() {
        scheduler.shutdown();
        try {
            Instant now = Instant.now();
            int n = closeOpenEntry(now);
            if (n > 0) {
                log.info("Server uptime log closed at {}", now);
            }
        } catch (Exception e) {
            log.warn("Failed to close uptime log entry on shutdown: {}", e.getMessage());
        }
    }

    public Optional<ServerUptimeLog> currentOpen() {
        return repository.findFirstByStoppedAtIsNullOrderByStartedAtDesc();
    }

    private void heartbeat() {
        try {
            String sql = "UPDATE server_uptime_log SET last_seen = now() " +
                    "WHERE id = (SELECT id FROM server_uptime_log WHERE stopped_at IS NULL ORDER BY started_at DESC LIMIT 1)";
            try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Uptime heartbeat failed: {}", e.getMessage());
        }
    }

    private void closeStaleOpen() throws java.sql.SQLException {
        String sql = "UPDATE server_uptime_log SET stopped_at = COALESCE(last_seen, started_at), " +
                "last_seen = COALESCE(last_seen, started_at) WHERE stopped_at IS NULL";
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private int closeOpenEntry(Instant stoppedAt) throws java.sql.SQLException {
        String sql = "UPDATE server_uptime_log SET stopped_at = ?, last_seen = ? " +
                "WHERE id = (SELECT id FROM server_uptime_log WHERE stopped_at IS NULL ORDER BY started_at DESC LIMIT 1)";
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(stoppedAt));
            ps.setTimestamp(2, Timestamp.from(stoppedAt));
            return ps.executeUpdate();
        }
    }
}