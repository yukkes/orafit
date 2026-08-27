package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Regression coverage for physical JDBC session isolation after a failed transaction. */
final class SessionResetTest {
    @Test
    void failedTransactionDoesNotPoisonNextConnection() throws Exception {
        try (Connection failed = IntegrationSupport.open()) {
            failed.setAutoCommit(false);
            try (Statement statement = failed.createStatement()) {
                assertThrows(
                        SQLException.class,
                        () -> statement.executeQuery("SELECT * FROM table_that_does_not_exist"));
            }
        }

        try (Connection next = IntegrationSupport.open();
                Statement statement = next.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}
