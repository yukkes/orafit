package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;

final class PriorityTwoCompatibilityTest {
    @Test
    void unsupportedSequenceShapesFailBeforeExecution() throws Exception {
        var engine = new io.github.orafit.OrafitEngine();
        for (String sql :
                java.util.List.of(
                        "SELECT q.*, s.NEXTVAL, s.NEXTVAL FROM q",
                        "SELECT s.NEXTVAL, s.NEXTVAL FROM dual ORDER BY 1",
                        "SELECT (SELECT s.NEXTVAL FROM dual), s.NEXTVAL FROM dual",
                        "INSERT INTO q (a,b) VALUES (s.NEXTVAL,S.NEXTVAL)")) {
            assertEquals(
                    "SEQUENCE_PROJECTION",
                    assertThrows(
                                    io.github.orafit.translation.TranslationException.class,
                                    () -> engine.translate(sql))
                            .code(),
                    sql);
        }
    }

    @Test
    void fetchBindsTruncateAndCanBeResetToNull() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT n FROM (SELECT 1 n FROM dual UNION ALL SELECT 2 n FROM dual UNION ALL SELECT 3 n FROM dual) q ORDER BY n OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")) {
            statement.setBigDecimal(1, new BigDecimal("1.9"));
            statement.setBigDecimal(2, new BigDecimal("2.9"));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertFalse(rows.next());
            }
            statement.setNull(2, Types.NUMERIC);
            try (ResultSet rows = statement.executeQuery()) {
                assertFalse(rows.next());
            }
            statement.setInt(2, -1);
            try (ResultSet rows = statement.executeQuery()) {
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void repeatedSequenceConsumesOneValuePerRowWithoutChangingBindOrder() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TEMP SEQUENCE p2_seq START 1");
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT ? AS marker, q.n, p2_seq.NEXTVAL AS a, P2_SEQ.NEXTVAL AS b FROM (SELECT 1 n FROM dual UNION ALL SELECT 2 n FROM dual UNION ALL SELECT 3 n FROM dual) q WHERE q.n <= ?")) {
                statement.setInt(1, 77);
                statement.setInt(2, 2);
                int count = 0;
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        assertEquals(77, rows.getInt(1));
                        assertEquals(rows.getLong(3), rows.getLong(4));
                        count++;
                    }
                }
                assertEquals(2, count);
                statement.setInt(2, 0);
                try (ResultSet rows = statement.executeQuery()) {
                    assertFalse(rows.next());
                }
                try (ResultSet rows = ddl.executeQuery("SELECT p2_seq.CURRVAL FROM dual")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getLong(1));
                }
            }
        }
    }

    @Test
    void nvlEvaluatesFallbackAndStatementRecoversAfterError() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT NVL(?, 1/?) AS value FROM dual")) {
            connection.setAutoCommit(false);
            statement.setInt(1, 7);
            statement.setInt(2, 0);
            assertEquals(
                    1476, assertThrows(SQLException.class, statement::executeQuery).getErrorCode());
            statement.setInt(2, 2);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
            statement.setNull(1, Types.NUMERIC);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, new BigDecimal("0.5").compareTo(rows.getBigDecimal(1)));
            }
        }
    }
}
