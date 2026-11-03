package ru.sensoterra.server.cli;

import org.mindrot.jbcrypt.BCrypt;
import ru.sensoterra.server.security.CredentialsPolicy;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Admin accounts are managed only here, from a shell on the server:
 * <pre>
 *   docker compose exec server /app/bin/sensoterra-server admin create &lt;username&gt;
 * </pre>
 * Having that shell (SSH to the VPS) is the proof of authority, so there is no HTTP way to
 * create or promote an admin. Plain JDBC on purpose: a Micronaut context would also start
 * the MQTT client with the server's client id and kick the running server off the broker.
 * The schema must exist, i.e. the server has started once and Flyway has run.
 */
public final class AdminCli {

    public static final String COMMAND = "admin";

    private static final int BCRYPT_COST = 12;
    private static final String USAGE = """
            Usage: sensoterra-server admin <command> [username]
              create <username>          create an ADMIN account (asks for the password)
              promote <username>         make an existing account ADMIN
              demote <username>          make an ADMIN a regular USER
              reset-password <username>  set a new password (asks for it)
              reset-2fa <username>       forget the TOTP secret; enrollment on next sign-in
              list                       list admin accounts
            Database: DB_URL, DB_USER, DB_PASSWORD (same as the server).""";

    /** Where passwords come from: the terminal, or stdin lines when there is none. */
    public interface PasswordSource {
        char[] read(String prompt) throws IOException;
    }

    private final Connection db;
    private final PasswordSource passwords;
    private final PrintStream out;

    public AdminCli(Connection db, PasswordSource passwords, PrintStream out) {
        this.db = db;
        this.passwords = passwords;
        this.out = out;
    }

    /** Entry from Application.main: {@code args[0]} is "admin". */
    public static void main(String[] args) {
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        if (rest.isEmpty() || rest.contains("-h") || rest.contains("--help")) {
            System.out.println(USAGE);
            System.exit(rest.isEmpty() ? 2 : 0);
        }
        String url = env("DB_URL", "jdbc:postgresql://localhost:5432/sensoterra");
        int code;
        try (Connection db = DriverManager.getConnection(url, env("DB_USER", null), env("DB_PASSWORD", null))) {
            code = new AdminCli(db, terminalPasswords(), System.out).execute(rest);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    /** Runs one command; returns the process exit code. */
    public int execute(List<String> args) {
        String command = args.get(0);
        String username = args.size() > 1 ? args.get(1) : null;
        try {
            requireSchema();
            // All statements of a command apply together or not at all.
            db.setAutoCommit(false);
            switch (command) {
                case "list" -> list();
                case "create" -> create(requireName(username));
                case "promote" -> promote(requireName(username));
                case "demote" -> demote(requireName(username));
                case "reset-password" -> resetPassword(requireName(username));
                case "reset-2fa" -> resetTotp(requireName(username));
                default -> {
                    out.println("Unknown command: " + command);
                    out.println(USAGE);
                    return 2;
                }
            }
            db.commit();
            return 0;
        } catch (CliException | SQLException | IOException e) {
            rollback();
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void rollback() {
        try {
            if (!db.getAutoCommit()) {
                db.rollback();
            }
        } catch (SQLException ignored) {
            // Nothing more to do: the connection is closed right after.
        }
    }

    private void list() throws SQLException {
        try (PreparedStatement st = db.prepareStatement(
                "SELECT username, enabled, totp_secret IS NOT NULL FROM app_user WHERE role = 'ADMIN' ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            int n = 0;
            while (rs.next()) {
                n++;
                out.printf("%-30s %-9s 2FA: %s%n", rs.getString(1),
                        rs.getBoolean(2) ? "enabled" : "DISABLED", rs.getBoolean(3) ? "set up" : "not set up");
            }
            if (n == 0) {
                out.println("No admin accounts.");
            }
        }
    }

    private void create(String username) throws SQLException, IOException {
        String name = policy(() -> CredentialsPolicy.requireUsername(username));
        if (findUser(name) != null) {
            throw new CliException("Account '" + name + "' already exists; use 'promote' to make it ADMIN");
        }
        String hash = newPasswordHash(name);
        long id;
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO app_user (username, password_hash, role, enabled, created_at) VALUES (?, ?, 'ADMIN', TRUE, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, name);
            st.setString(2, hash);
            st.setTimestamp(3, Timestamp.from(Instant.now()));
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        // Same as UserPrefService.seedDefaults: channel labels from the defaults table.
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO user_field_pref (user_id, sensor_type, field, label, unit) "
                        + "SELECT ?, sensor_type, field, label, unit FROM sensor_field_default")) {
            st.setLong(1, id);
            st.executeUpdate();
        }
        audit(id, name, "CLI_CREATE_ADMIN");
        out.println("Admin '" + name + "' created. Two-factor authentication is set up on the first sign-in.");
    }

    private void promote(String username) throws SQLException {
        User user = requireUser(username);
        if (user.admin()) {
            throw new CliException("'" + user.name() + "' is already ADMIN");
        }
        update("UPDATE app_user SET role = 'ADMIN', totp_secret = NULL, totp_last_step = NULL WHERE id = ?", user.id());
        audit(user.id(), user.name(), "CLI_PROMOTE");
        out.println("'" + user.name() + "' is ADMIN now. Their open sessions end; 2FA is set up on the next sign-in.");
    }

    private void demote(String username) throws SQLException {
        User user = requireUser(username);
        if (!user.admin()) {
            throw new CliException("'" + user.name() + "' is not ADMIN");
        }
        try (PreparedStatement st = db.prepareStatement(
                "SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE AND id <> ?")) {
            st.setLong(1, user.id());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                if (rs.getLong(1) == 0) {
                    throw new CliException("'" + user.name() + "' is the last enabled admin");
                }
            }
        }
        update("UPDATE app_user SET role = 'USER', totp_secret = NULL, totp_last_step = NULL WHERE id = ?", user.id());
        update("DELETE FROM admin_recovery_code WHERE user_id = ?", user.id());
        audit(user.id(), user.name(), "CLI_DEMOTE");
        out.println("'" + user.name() + "' is a regular USER now.");
    }

    private void resetPassword(String username) throws SQLException, IOException {
        User user = requireUser(username);
        String hash = newPasswordHash(user.name());
        try (PreparedStatement st = db.prepareStatement("UPDATE app_user SET password_hash = ? WHERE id = ?")) {
            st.setString(1, hash);
            st.setLong(2, user.id());
            st.executeUpdate();
        }
        audit(user.id(), user.name(), "CLI_RESET_PASSWORD");
        out.println("Password of '" + user.name() + "' changed.");
    }

    private void resetTotp(String username) throws SQLException {
        User user = requireUser(username);
        update("UPDATE app_user SET totp_secret = NULL, totp_last_step = NULL WHERE id = ?", user.id());
        update("DELETE FROM admin_recovery_code WHERE user_id = ?", user.id());
        audit(user.id(), user.name(), "CLI_RESET_2FA");
        out.println("2FA of '" + user.name() + "' reset: a new authenticator is set up on the next sign-in.");
    }

    private String newPasswordHash(String username) throws IOException {
        char[] first = passwords.read("Password for " + username + ": ");
        char[] second = passwords.read("Repeat password: ");
        try {
            if (first == null || second == null) {
                throw new CliException("No password given");
            }
            if (!Arrays.equals(first, second)) {
                throw new CliException("Passwords do not match");
            }
            String password = new String(first);
            policy(() -> {
                CredentialsPolicy.requirePassword(password, username);
                return null;
            });
            return BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST));
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    /** CredentialsPolicy reports with HTTP exceptions; here they are just messages. */
    private static <T> T policy(java.util.function.Supplier<T> check) {
        try {
            return check.get();
        } catch (RuntimeException e) {
            throw new CliException(e.getMessage());
        }
    }

    private record User(long id, String name, boolean admin) {
    }

    private User findUser(String username) throws SQLException {
        try (PreparedStatement st = db.prepareStatement("SELECT id, username, role FROM app_user WHERE username = ?")) {
            st.setString(1, username.trim());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new User(rs.getLong(1), rs.getString(2), "ADMIN".equals(rs.getString(3))) : null;
            }
        }
    }

    private User requireUser(String username) throws SQLException {
        User user = findUser(username);
        if (user == null) {
            throw new CliException("No account '" + username + "'");
        }
        return user;
    }

    private static String requireName(String username) {
        if (username == null || username.isBlank()) {
            throw new CliException("Username is required");
        }
        return username;
    }

    private void update(String sql, long id) throws SQLException {
        try (PreparedStatement st = db.prepareStatement(sql)) {
            st.setLong(1, id);
            st.executeUpdate();
        }
    }

    /** Same table and columns as AdminAuditService; ip says where the change came from. */
    private void audit(long userId, String username, String event) throws SQLException {
        try (PreparedStatement st = db.prepareStatement(
                "INSERT INTO admin_audit (user_id, username, created_at, event, ip, detail) VALUES (?, ?, ?, ?, 'cli', ?)")) {
            st.setLong(1, userId);
            st.setString(2, username);
            st.setTimestamp(3, Timestamp.from(Instant.now()));
            st.setString(4, event);
            st.setString(5, "os user=" + System.getProperty("user.name"));
            st.executeUpdate();
        }
    }

    private void requireSchema() throws SQLException {
        try (PreparedStatement st = db.prepareStatement("SELECT 1 FROM admin_audit WHERE 1 = 0")) {
            st.executeQuery().close();
        } catch (SQLException e) {
            throw new CliException("Database schema is not ready — start the server once so migrations run ("
                    + e.getMessage() + ")");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Hidden input on a terminal; plain stdin lines otherwise (./gradlew run, pipes). */
    private static PasswordSource terminalPasswords() {
        Console console = System.console();
        if (console != null) {
            return prompt -> console.readPassword("%s", prompt);
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return prompt -> {
            System.out.print(prompt);
            System.out.flush();
            String line = in.readLine();
            return line == null ? null : line.toCharArray();
        };
    }

    private static final class CliException extends RuntimeException {
        CliException(String message) {
            super(message);
        }
    }
}
