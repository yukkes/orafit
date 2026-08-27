package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Verifies Oracle-style statement rollback against a real PostgreSQL server. */
@Execution(ExecutionMode.SAME_THREAD)
final class OracleStatementRollbackPostgresIT {
    @Test
    void failedStatementDoesNotAbortTheTransaction() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE junit_statement_rollback(id integer primary key)");
            connection.setAutoCommit(false);
            try {
                assertEquals(
                        1,
                        statement.executeUpdate("INSERT INTO junit_statement_rollback VALUES (1)"));
                SQLException duplicate =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        statement.executeUpdate(
                                                "INSERT INTO junit_statement_rollback VALUES (1)"));
                assertTrue(duplicate.getSQLState() != null && !duplicate.getSQLState().isBlank());
                assertEquals(
                        1,
                        statement.executeUpdate("INSERT INTO junit_statement_rollback VALUES (2)"));
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }

            try (ResultSet result =
                    statement.executeQuery("SELECT id FROM junit_statement_rollback ORDER BY id")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
                assertFalse(result.next());
            }
        }
    }
}
