package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Minimal reproduction for PostgreSQL query cancellation through Orafit's JDBC boundary. */
final class OrafitTimeoutReproducerTest {
    @Test
    void cancelsLongRunningStatementAtConfiguredTimeout() throws Exception {
        try (var connection = IntegrationSupport.open();
                PreparedStatement statement = connection.prepareStatement("SELECT pg_sleep(2)")) {
            statement.setQueryTimeout(1);
            try {
                statement.executeQuery();
                fail("the statement must be cancelled by the timeout");
            } catch (SQLException failure) {
                assertTrue(
                        failure.getMessage().contains("canceling statement"),
                        () -> "unexpected timeout error: " + failure.getMessage());
            }
        }
    }
}
