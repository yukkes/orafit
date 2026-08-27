package io.github.orafit.compat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.orafit.OrafitEngine;
import io.github.orafit.compat.CompatibilityExecutor.ColumnObservation;
import io.github.orafit.compat.CompatibilityExecutor.Observation;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class OracleDifferentialIT {
    private final OrafitEngine engine = new OrafitEngine();
    private Connection oracle;
    private Target target;
    private EmbeddedPostgres embeddedPostgres;

    @BeforeAll
    void connect() throws Exception {
        Class.forName("org.postgresql.Driver");
        Class.forName("io.github.orafit.jdbc.OrafitDriver");

        CompletableFuture<EmbeddedPostgres> postgresStartup =
                CompletableFuture.supplyAsync(OracleDifferentialIT::startEmbeddedPostgres);
        oracle =
                connectOracleWithRetry(
                        required("ORACLE_JDBC_URL"),
                        required("ORACLE_USER"),
                        required("ORACLE_PASSWORD"),
                        Duration.ofSeconds(30));
        oracle.setAutoCommit(false);
        DatabaseMetaData metadata = oracle.getMetaData();
        String product = metadata.getDatabaseProductName();
        String version = metadata.getDatabaseProductVersion();
        int major = firstMajor(version);
        System.out.println("Oracle target probe product: " + product);
        System.out.println("Oracle target probe version: " + version);
        System.out.println("Oracle target probe major: " + major + " (expected 18)");
        assertEquals(
                18,
                major,
                () ->
                        "Oracle release authority must be 18c XE, observed "
                                + product
                                + " "
                                + version);
        configureOracle(oracle);
        setupOracle(oracle);
        oracle.commit();

        embeddedPostgres = awaitPostgres(postgresStartup);
        installPlainRuntime();
        target =
                target(
                        "PostgreSQL 17.10 embedded",
                        "jdbc:orafit://127.0.0.1:"
                                + embeddedPostgres.getPort()
                                + "/postgres?sslmode=disable",
                        "postgres",
                        "postgres");
        System.out.println("Canonical compatibility cases: " + CompatibilityCases.list().size());
    }

    Stream<CompatibilityCase> cases() {
        return CompatibilityCases.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesOracle18(CompatibilityCase test) {
        CompatibilityExecutor oracleExecutor =
                new CompatibilityExecutor(CompatibilityExecutor.Backend.ORACLE, oracle);
        Observation expected = oracleExecutor.execute(test);
        assertOracleExpectation(test, expected);

        if (test.mode() == CompatibilityCase.Mode.REJECT
                && test.rejectStage() == CompatibilityCase.RejectStage.TRANSLATION) {
            assertEquals(
                    test.orafitCode(),
                    CompatibilityExecutor.translationRejectCode(engine, test),
                    () -> test.id() + " must fail closed during translation");
            return;
        }

        Observation actual = target.executor().execute(test);
        if (test.mode() == CompatibilityCase.Mode.REJECT) {
            assertRuntimeReject(test, target, actual);
        } else {
            assertSame(test, target, expected, actual);
        }
    }

    @AfterEach
    void rollback() throws Exception {
        if (oracle != null) {
            oracle.rollback();
        }
        if (target != null) {
            target.connection().rollback();
        }
    }

    @AfterAll
    void close() throws Exception {
        Exception first = null;
        if (target != null) {
            try {
                target.connection().close();
            } catch (Exception ex) {
                first = ex;
            }
        }
        if (oracle != null) {
            try {
                oracle.close();
            } catch (Exception ex) {
                if (first == null) {
                    first = ex;
                } else {
                    first.addSuppressed(ex);
                }
            }
        }
        if (embeddedPostgres != null) {
            try {
                embeddedPostgres.close();
            } catch (Exception ex) {
                if (first == null) {
                    first = ex;
                } else {
                    first.addSuppressed(ex);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static Connection connectOracle(String url, String user, String password)
            throws Exception {
        Driver driver =
                (Driver)
                        Class.forName("oracle.jdbc.OracleDriver")
                                .getDeclaredConstructor()
                                .newInstance();
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new IllegalArgumentException("Oracle JDBC driver rejected URL: " + url);
        }
        return connection;
    }

    private static Connection connectOracleWithRetry(
            String url, String user, String password, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                return connectOracle(url, user, password);
            } catch (SQLException ex) {
                if (!isOracleStarting(ex) || System.nanoTime() >= deadline) {
                    throw ex;
                }
                Thread.sleep(250L);
            }
        }
    }

    private static boolean isOracleStarting(SQLException failure) {
        for (SQLException current = failure;
                current != null;
                current = current.getNextException()) {
            switch (current.getErrorCode()) {
                case 1017, 1033, 1034, 1109, 12505, 12514, 12541, 17002, 17410, 17800 -> {
                    return true;
                }
                default -> {
                    // Continue through chained Oracle errors.
                }
            }
            if (current.getSQLState() != null && current.getSQLState().startsWith("08")) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains("ORA-17800")) {
                return true;
            }
        }
        return false;
    }

    private static EmbeddedPostgres startEmbeddedPostgres() {
        try {
            return EmbeddedPostgres.builder()
                    .setPort(0)
                    .setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false)
                    .setPGStartupWait(Duration.ofSeconds(30))
                    .start();
        } catch (Exception ex) {
            throw new CompletionException(ex);
        }
    }

    private static EmbeddedPostgres awaitPostgres(
            CompletableFuture<EmbeddedPostgres> postgresStartup) throws Exception {
        try {
            return postgresStartup.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private static Target target(String label, String url, String user, String password)
            throws Exception {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
        setupPostgres(connection);
        connection.commit();
        return new Target(
                label,
                connection,
                new CompatibilityExecutor(CompatibilityExecutor.Backend.ORAFIT, connection));
    }

    private static void assertOracleExpectation(CompatibilityCase test, Observation observed) {
        if (test.oracleOutcome() == CompatibilityCase.Outcome.SUCCESS) {
            assertEquals(
                    "ok",
                    observed.status(),
                    () ->
                            test.id()
                                    + " is declared Oracle-success but Oracle 18c returned "
                                    + observed.error());
            return;
        }
        assertEquals(
                "error",
                observed.status(),
                () -> test.id() + " is declared Oracle-error but Oracle 18c succeeded");
        assertNotNull(observed.error(), test.id() + " Oracle error details");
        if (test.oracleErrorCode() != null) {
            assertEquals(
                    test.oracleErrorCode().intValue(),
                    observed.error().code(),
                    () -> test.id() + " Oracle vendor error code");
        }
    }

    private static void assertRuntimeReject(
            CompatibilityCase test, Target target, Observation actual) {
        assertEquals(
                "error", actual.status(), () -> target.label() + " unsafe-accepted " + test.id());
        assertNotNull(actual.error(), target.label() + " error details for " + test.id());
        assertEquals(
                test.orafitCode(),
                actual.error().orafitCode(),
                () -> target.label() + " rejection code for " + test.id());
    }

    private static void assertSame(
            CompatibilityCase test, Target target, Observation expected, Observation actual) {
        String prefix = target.label() + " / " + test.id() + ": ";
        assertEquals(expected.status(), actual.status(), prefix + "status");
        if ("error".equals(expected.status())) {
            assertNotNull(expected.error(), prefix + "Oracle error");
            assertNotNull(actual.error(), prefix + "Orafit error");
            assertEquals(
                    expected.error().code(), actual.error().code(), prefix + "vendor error code");
            assertEquals(
                    expected.error().sqlState(), actual.error().sqlState(), prefix + "SQLState");
            return;
        }

        switch (test.compare()) {
            case ORDERED, UNORDERED ->
                    assertAll(
                            prefix,
                            () -> assertEquals(expected.rows(), actual.rows(), prefix + "rows"),
                            () ->
                                    assertEquals(
                                            comparableColumns(
                                                    expected.columns(), test.compareTypeName()),
                                            comparableColumns(
                                                    actual.columns(), test.compareTypeName()),
                                            prefix + "JDBC metadata"));
            case SHAPE ->
                    assertAll(
                            prefix,
                            () ->
                                    assertEquals(
                                            shape(expected.rows()),
                                            shape(actual.rows()),
                                            prefix + "row shape"),
                            () ->
                                    assertEquals(
                                            expected.columns().size(),
                                            actual.columns().size(),
                                            prefix + "column count"));
            case UPDATE ->
                    assertAll(
                            prefix,
                            () ->
                                    assertEquals(
                                            expected.updateCount(),
                                            actual.updateCount(),
                                            prefix + "update count"),
                            () ->
                                    assertEquals(
                                            expected.postState(),
                                            actual.postState(),
                                            prefix + "DML post-state"));
            case CALL ->
                    assertAll(
                            prefix,
                            () -> assertEquals(expected.out(), actual.out(), prefix + "OUT values"),
                            () ->
                                    assertEquals(
                                            expected.updateCount(),
                                            actual.updateCount(),
                                            prefix + "update count"));
            case RETURNING ->
                    assertAll(
                            prefix,
                            () ->
                                    assertTrue(
                                            returningEqual(
                                                    expected.returningRows(),
                                                    actual.returningRows()),
                                            () ->
                                                    prefix
                                                            + "RETURNING rows expected="
                                                            + expected.returningRows()
                                                            + " actual="
                                                            + actual.returningRows()),
                            () ->
                                    assertEquals(
                                            expected.updateCount(),
                                            actual.updateCount(),
                                            prefix + "update count"),
                            () ->
                                    assertEquals(
                                            expected.postState(),
                                            actual.postState(),
                                            prefix + "DML post-state"));
            case ERROR -> {
                // The error path is handled above before compare-mode dispatch.
            }
            default -> fail(prefix + "unknown compare mode " + test.compare());
        }
    }

    private static List<ComparableColumn> comparableColumns(
            List<ColumnObservation> columns, boolean typeName) {
        return columns.stream()
                .map(
                        column ->
                                new ComparableColumn(
                                        column.label(),
                                        column.name(),
                                        column.jdbcType(),
                                        typeName ? column.typeName() : null,
                                        column.precision(),
                                        column.scale(),
                                        column.nullable()))
                .toList();
    }

    private static Shape shape(List<List<Object>> rows) {
        return new Shape(rows.size(), rows.stream().map(List::size).toList());
    }

    private static boolean returningEqual(List<List<Object>> expected, List<List<Object>> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int row = 0; row < expected.size(); row++) {
            List<Object> left = expected.get(row);
            List<Object> right = actual.get(row);
            if (left.size() != right.size()) {
                return false;
            }
            for (int column = 0; column < left.size(); column++) {
                if (!valueEqual(left.get(column), right.get(column))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean valueEqual(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (!(left instanceof String leftText) || !(right instanceof String rightText)) {
            return false;
        }
        try {
            return new BigDecimal(leftText).compareTo(new BigDecimal(rightText)) == 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static void configureOracle(Connection connection) throws Exception {
        List<String> statements =
                List.of(
                        "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD'",
                        "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9'",
                        "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM'",
                        "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'",
                        "ALTER SESSION SET NLS_DATE_LANGUAGE = 'AMERICAN'",
                        "ALTER SESSION SET NLS_SORT = 'BINARY'",
                        "ALTER SESSION SET NLS_COMP = 'BINARY'",
                        "ALTER SESSION SET TIME_ZONE = '+00:00'");
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void setupOracle(Connection connection) throws Exception {
        executeFixture(connection, "schema.sql");
    }

    private static void setupPostgres(Connection connection) throws Exception {
        executeFixture(connection, "postgres.sql");
    }

    private void installPlainRuntime() throws Exception {
        Path directory =
                Path.of(System.getProperty("user.dir"))
                        .resolve("target/orafit/plain")
                        .toAbsolutePath()
                        .normalize();
        List<Path> runtimes;
        try (Stream<Path> files = Files.list(directory)) {
            runtimes =
                    files.filter(Files::isRegularFile)
                            .filter(
                                    path -> {
                                        String name = path.getFileName().toString();
                                        return name.startsWith("orafit--") && name.endsWith(".sql");
                                    })
                            .toList();
        }
        if (runtimes.size() != 1) {
            throw new IllegalStateException("Expected one plain runtime, found " + runtimes);
        }
        try (Connection connection = embeddedPostgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(runtimes.get(0), StandardCharsets.UTF_8));
        }
    }

    private static void executeFixture(Connection connection, String file) throws Exception {
        Path fixture =
                Path.of(System.getProperty("user.dir"))
                        .resolve("src/test/resources/oracle/fixtures")
                        .resolve(file);
        String text = Files.readString(fixture, StandardCharsets.UTF_8);
        String marker = "-- @statement";
        int position = text.indexOf(marker);
        while (position >= 0) {
            int start = position + marker.length();
            int next = text.indexOf(marker, start);
            String sql = text.substring(start, next < 0 ? text.length() : next).trim();
            if (!sql.isEmpty()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            position = next;
        }
    }

    private static int firstMajor(String version) {
        if (version == null) {
            return -1;
        }
        for (int i = 0; i < version.length(); i++) {
            if (!Character.isDigit(version.charAt(i))) {
                continue;
            }
            int value = 0;
            while (i < version.length() && Character.isDigit(version.charAt(i))) {
                value = value * 10 + version.charAt(i) - '0';
                i++;
            }
            return value;
        }
        return -1;
    }

    private static String required(String environment) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(environment + " is required");
        }
        return value;
    }

    private record Target(String label, Connection connection, CompatibilityExecutor executor) {}

    private record ComparableColumn(
            String label,
            String name,
            int jdbcType,
            String typeName,
            int precision,
            int scale,
            int nullable) {}

    private record Shape(int rows, List<Integer> widths) {}
}
