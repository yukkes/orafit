package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.orafit.compat.CompatibilityCase;
import io.github.orafit.compat.CompatibilityCases;
import io.github.orafit.compat.CompatibilityExecutor;
import io.github.orafit.compat.CompatibilityExecutor.Observation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ApplicationRegressionJdbcTest {
    private Connection connection;
    private CompatibilityExecutor executor;

    Stream<CompatibilityCase> cases() {
        return CompatibilityCases.all()
                .filter(test -> "application-sql-regressions".equals(test.feature()));
    }

    @BeforeEach
    void resetFixture() throws Exception {
        String orafitUrl = IntegrationSupport.jdbcUrl();
        String postgresUrl =
                orafitUrl.startsWith("jdbc:orafit:")
                        ? "jdbc:postgresql:" + orafitUrl.substring("jdbc:orafit:".length())
                        : orafitUrl;
        try (Connection setup =
                java.sql.DriverManager.getConnection(
                        postgresUrl,
                        IntegrationSupport.jdbcUser(),
                        IntegrationSupport.jdbcPassword())) {
            executeFixture(setup, "postgres.sql");
        }
        connection = IntegrationSupport.open();
        executor = new CompatibilityExecutor(CompatibilityExecutor.Backend.ORAFIT, connection);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void oracleSuccessfulApplicationSqlExecutesOnTarget(CompatibilityCase test) {
        assertEquals(CompatibilityCase.Outcome.SUCCESS, test.oracleOutcome(), test.id());
        assertEquals(CompatibilityCase.Mode.SAME, test.mode(), test.id());
        Observation actual = executor.execute(test);
        assertEquals("ok", actual.status(), () -> test.id() + ": " + actual.error());
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) connection.close();
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
}
