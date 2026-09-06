package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

final class PriorityThreeValidationTest {
    @Test
    void realColumnNamedNextvalRemainsAColumn() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE p3_columns (nextval numeric)");
            statement.execute("INSERT INTO p3_columns VALUES (1)");
            try (ResultSet rows =
                    statement.executeQuery(
                            "SELECT 1 AS value FROM p3_columns q WHERE q.nextval = 1")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void whereRejectionDoesNotConsumeSequenceOrBreakValidDml() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP SEQUENCE p3_seq START 1");
            statement.execute("CREATE TEMP TABLE p3_rows (id numeric)");
            assertEquals(
                    2287,
                    assertThrows(
                                    SQLException.class,
                                    () ->
                                            statement.executeQuery(
                                                    "SELECT 1 FROM dual WHERE p3_seq.NEXTVAL > 0"))
                            .getErrorCode());
            assertEquals(
                    2287,
                    assertThrows(
                                    SQLException.class,
                                    () ->
                                            connection.prepareStatement(
                                                    "SELECT ? FROM dual WHERE P3_SEQ.NEXTVAL > ?"))
                            .getErrorCode());
            try (ResultSet rows = statement.executeQuery("SELECT p3_seq.NEXTVAL FROM dual")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
            assertEquals(1, statement.executeUpdate("INSERT INTO p3_rows VALUES (p3_seq.NEXTVAL)"));
            assertEquals(1, statement.executeUpdate("UPDATE p3_rows SET id = p3_seq.NEXTVAL"));
            try (ResultSet rows = statement.executeQuery("SELECT id FROM p3_rows")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getLong(1));
            }
        }
    }

    @Test
    void invalidYearBindRaisesOracleErrorAndCanBeReset() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT TO_DATE(?, 'YYYY-MM-DD') AS value FROM dual")) {
            connection.setAutoCommit(false);
            statement.setString(1, "0000-01-01");
            SQLException failure = assertThrows(SQLException.class, statement::executeQuery);
            assertEquals(1841, failure.getErrorCode());
            assertEquals("22008", failure.getSQLState());
            statement.setString(1, "2024-02-29");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("2024-02-29 00:00:00.0", rows.getTimestamp(1).toString());
            }
            statement.setNull(1, Types.VARCHAR);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getTimestamp(1));
                assertTrue(rows.wasNull());
            }
        }
    }
}
