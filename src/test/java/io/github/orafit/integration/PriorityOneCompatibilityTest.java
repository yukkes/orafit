package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;

final class PriorityOneCompatibilityTest {
    @Test
    void divisionRetainsBindOrderNullAndStatementRecovery() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT ? / ? AS value FROM dual")) {
            connection.setAutoCommit(false);
            statement.setInt(1, 1);
            statement.setInt(2, 3);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(
                        0,
                        new BigDecimal("0.3333333333333333333333333333333333333333")
                                .compareTo(rows.getBigDecimal(1)));
            }
            statement.setInt(2, 0);
            assertEquals(
                    1476, assertThrows(SQLException.class, statement::executeQuery).getErrorCode());
            statement.setInt(2, 8);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, new BigDecimal("0.125").compareTo(rows.getBigDecimal(1)));
            }
            statement.setNull(1, Types.NUMERIC);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getBigDecimal(1));
                assertTrue(rows.wasNull());
            }
        }
    }

    @Test
    void dateColumnDifferenceOwnsNumericMetadata() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE p1_dates (a date, b date)");
            statement.execute("INSERT INTO p1_dates VALUES (DATE '2024-03-02', DATE '2024-03-01')");
            try (ResultSet rows = statement.executeQuery("SELECT a - b AS value FROM p1_dates")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(Types.NUMERIC, rows.getMetaData().getColumnType(1));
                assertEquals(0, rows.getMetaData().getScale(1));
            }
        }
    }

    @Test
    void fixedCharColumnsRetainPaddingAgainstVarchar() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE p1_chars (c char(3), v varchar(3))");
            statement.execute("INSERT INTO p1_chars VALUES ('A', 'A')");
            try (ResultSet rows =
                    statement.executeQuery(
                            "SELECT LENGTH(c), CASE WHEN c = v THEN 1 ELSE 0 END FROM p1_chars")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertEquals(0, rows.getInt(2));
            }
        }
    }
}
