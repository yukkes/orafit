package io.github.orafit.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

final class IntegrationSupport {
    private static final Object LOCK = new Object();
    private static final Path ROOT =
            Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."))
                    .toAbsolutePath()
                    .normalize();
    private static final String RUNTIME = System.getProperty("orafit.test.runtime", "plain");
    private static volatile boolean initialized;
    private static EmbeddedPostgres embeddedPostgres;
    private static String jdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;

    private IntegrationSupport() {}

    static Connection open() throws Exception {
        initialize();
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }

    static String jdbcUrl() throws Exception {
        initialize();
        return jdbcUrl;
    }

    static String jdbcUser() {
        return jdbcUser == null ? user() : jdbcUser;
    }

    static String jdbcPassword() {
        return jdbcPassword == null ? password() : jdbcPassword;
    }

    private static void initialize() throws Exception {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            Class.forName("io.github.orafit.jdbc.OrafitDriver");
            jdbcUser = user();
            jdbcPassword = password();
            String configuredUrl = configuredJdbcUrl();
            if (configuredUrl == null) {
                startEmbeddedPostgres();
            } else {
                connectPostgres(configuredUrl);
            }
            initialized = true;
        }
    }

    private static void startEmbeddedPostgres() throws Exception {
        embeddedPostgres =
                EmbeddedPostgres.builder()
                        .setPort(0)
                        .setCleanDataDirectory(true)
                        .setRegisterShutdownHook(false)
                        .setPGStartupWait(Duration.ofSeconds(30))
                        .start();
        jdbcPassword = "postgres";
        jdbcUrl =
                "jdbc:orafit://127.0.0.1:"
                        + embeddedPostgres.getPort()
                        + "/postgres?sslmode=disable";
        Runtime.getRuntime().addShutdownHook(new Thread(IntegrationSupport::stopEmbeddedPostgres));
        if ("plain".equals(RUNTIME)) {
            try (Connection connection = embeddedPostgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(generatedRuntimeSql(), StandardCharsets.UTF_8));
            }
        } else if (!"existing".equals(RUNTIME)) {
            throw new IllegalArgumentException("Unknown orafit.test.runtime: " + RUNTIME);
        }
    }

    private static void connectPostgres(String url) throws Exception {
        jdbcUrl = url;
        String setupUrl =
                jdbcUrl.startsWith("jdbc:orafit:")
                        ? "jdbc:postgresql:" + jdbcUrl.substring("jdbc:orafit:".length())
                        : jdbcUrl;
        try (Connection connection =
                DriverManager.getConnection(setupUrl, jdbcUser, jdbcPassword)) {
            if ("plain".equals(RUNTIME)) {
                String sql = Files.readString(generatedRuntimeSql(), StandardCharsets.UTF_8);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            } else if (!"existing".equals(RUNTIME)) {
                throw new IllegalArgumentException("Unknown orafit.test.runtime: " + RUNTIME);
            }
        }
    }

    private static Path generatedRuntimeSql() throws IOException {
        Path directory = ROOT.resolve("target/orafit/plain");
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("orafit plain runtime was not generated");
        }
        List<Path> matches;
        try (var files = Files.list(directory)) {
            matches =
                    files.filter(Files::isRegularFile)
                            .filter(IntegrationSupport::isRuntimeSql)
                            .toList();
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected one generated plain orafit runtime, found " + matches);
        }
        return matches.get(0);
    }

    private static boolean isRuntimeSql(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("orafit--") && name.endsWith(".sql");
    }

    private static String configuredJdbcUrl() {
        return firstNonBlank(
                System.getProperty("orafit.test.jdbcUrl"), System.getenv("ORAFIT_JDBC_URL"));
    }

    private static String user() {
        return firstNonBlank(
                System.getProperty("orafit.test.jdbcUser"),
                System.getenv("ORAFIT_JDBC_USER"),
                "postgres");
    }

    private static String password() {
        return firstNonBlank(
                System.getProperty("orafit.test.jdbcPassword"),
                System.getenv("ORAFIT_JDBC_PASSWORD"),
                "");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static void stopEmbeddedPostgres() {
        EmbeddedPostgres server = embeddedPostgres;
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
            // The JVM is already shutting down; there is no useful recovery action.
        }
    }
}
