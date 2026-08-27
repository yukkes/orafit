package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Execution(ExecutionMode.SAME_THREAD)
final class PostgresJdbcTest {
    @Test
    void inListEnforcesOracleExpressionLimitAtTheJdbcBoundary() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            try (PreparedStatement statement = connection.prepareStatement(inList(1000))) {
                for (int index = 1; index <= 1000; index++) statement.setInt(index, index);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }

            SQLException exception =
                    assertThrows(
                            SQLException.class, () -> connection.prepareStatement(inList(1001)));
            assertEquals(1795, exception.getErrorCode());
            assertEquals("42000", exception.getSQLState());
            assertTrue(exception.getMessage().startsWith("ORA-01795:"));
        }
    }

    @Test
    void bareSequenceRestartUsesMinvalueAndPreservesSequenceOptions() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMP SEQUENCE junit_restart_sequence "
                            + "MINVALUE 5 MAXVALUE 100 START WITH 40 CACHE 5 NO CYCLE");
            statement.execute("ALTER SEQUENCE junit_restart_sequence RESTART");
            try (ResultSet result =
                    statement.executeQuery("SELECT junit_restart_sequence.NEXTVAL FROM DUAL")) {
                assertTrue(result.next());
                assertEquals(5, result.getLong(1));
            }

            statement.execute("ALTER SEQUENCE junit_restart_sequence RESTART START WITH 7");
            try (ResultSet result =
                    statement.executeQuery("SELECT junit_restart_sequence.NEXTVAL FROM DUAL")) {
                assertTrue(result.next());
                assertEquals(7, result.getLong(1));
            }
            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT cache_size, cycle FROM pg_sequences "
                                    + "WHERE sequencename = 'junit_restart_sequence'")) {
                assertTrue(result.next());
                assertEquals(5, result.getLong(1));
                assertFalse(result.getBoolean(2));
            }
        }
    }

    @Test
    void anonymousDmlBlockPreservesBindsAndRollsBackAsOneStatement() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute("CREATE TEMP TABLE junit_dml_block(id integer primary key, value text)");
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "BEGIN "
                                    + "INSERT INTO junit_dml_block VALUES (?, ?); "
                                    + "UPDATE junit_dml_block SET value = NVL(?, value) WHERE id = ?; "
                                    + "END;")) {
                statement.setInt(1, 1);
                statement.setString(2, "before");
                statement.setString(3, "after");
                statement.setInt(4, 1);
                assertEquals(1, statement.executeUpdate());
                assertEquals(1, statement.getUpdateCount());
            }
            try (ResultSet result =
                    setup.executeQuery("SELECT value FROM junit_dml_block WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals("after", result.getString(1));
                assertFalse(result.next());
            }

            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "BEGIN "
                                    + "INSERT INTO junit_dml_block VALUES (2, 'first'); "
                                    + "INSERT INTO junit_dml_block VALUES (1, 'duplicate'); "
                                    + "END;")) {
                assertThrows(SQLException.class, statement::executeUpdate);
            }
            try (ResultSet result =
                    setup.executeQuery("SELECT COUNT(*) FROM junit_dml_block WHERE id = 2")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }

            connection.setAutoCommit(false);
            setup.execute("INSERT INTO junit_dml_block VALUES (5, 'transaction-before')");
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "BEGIN "
                                    + "INSERT INTO junit_dml_block VALUES (6, 'block-before'); "
                                    + "INSERT INTO junit_dml_block VALUES (5, 'duplicate'); "
                                    + "END;")) {
                assertThrows(SQLException.class, statement::executeUpdate);
            }
            try (ResultSet result =
                    setup.executeQuery(
                            "SELECT id FROM junit_dml_block WHERE id IN (5, 6) ORDER BY id")) {
                assertTrue(result.next());
                assertEquals(5, result.getInt(1));
                assertFalse(result.next());
            }
            connection.rollback();
            connection.setAutoCommit(true);

            assertThrows(
                    SQLFeatureNotSupportedException.class,
                    () ->
                            setup.execute(
                                    "BEGIN "
                                            + "INSERT INTO junit_dml_block VALUES (3, 'one'); "
                                            + "INSERT INTO junit_dml_block VALUES (4, 'two'); "
                                            + "END;"));
        }
    }

    @Test
    void toCharNumberOmitsInsignificantDeclaredScale() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE junit_number_text(value numeric(6,3))");
            statement.execute("INSERT INTO junit_number_text VALUES (10), (10.010)");
            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT TO_CHAR(value) FROM junit_number_text ORDER BY value")) {
                assertTrue(result.next());
                assertEquals("10", result.getString(1));
                assertTrue(result.next());
                assertEquals("10.01", result.getString(1));
                assertFalse(result.next());
            }
            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT TO_CHAR(CAST(10 AS NUMBER(6,3))), "
                                    + "TO_CHAR(CAST(10.010 AS NUMBER(6,3))) FROM dual")) {
                assertTrue(result.next());
                assertEquals("10", result.getString(1));
                assertEquals("10.01", result.getString(2));
                assertFalse(result.next());
            }
            try (ResultSet result =
                    statement.executeQuery("SELECT value FROM junit_number_text ORDER BY value")) {
                assertTrue(result.next());
                assertEquals("10", result.getString(1));
                assertTrue(result.next());
                assertEquals("10.01", result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void fixedCharUsesOracleByteWidthForMultibyteValues() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE junit_char_byte(value char(15))");
            statement.execute("INSERT INTO junit_char_byte VALUES ('A'), ('３'), ('テキスト１')");
            try (ResultSet result =
                    statement.executeQuery("SELECT value FROM junit_char_byte ORDER BY value")) {
                assertTrue(result.next());
                assertEquals("A" + " ".repeat(14), result.getString(1));
                assertEquals("A" + " ".repeat(14), result.getObject(1));
                assertTrue(result.next());
                assertEquals("テキスト１", result.getString(1));
                assertEquals("テキスト１", result.getObject(1));
                assertTrue(result.next());
                assertEquals("３" + " ".repeat(12), result.getString(1));
                assertEquals("３" + " ".repeat(12), result.getObject(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void createViewPreservesOracleExpressionsAtExecutionTime() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP VIEW IF EXISTS orafit_junit_concat_view");
            try {
                statement.execute(
                        "CREATE VIEW orafit_junit_concat_view AS "
                                + "SELECT 'A' || NULL AS value FROM DUAL");
                try (ResultSet result =
                        statement.executeQuery("SELECT value FROM orafit_junit_concat_view")) {
                    assertTrue(result.next());
                    assertEquals("A", result.getString(1));
                    assertFalse(result.next());
                }
            } finally {
                statement.execute("DROP VIEW IF EXISTS orafit_junit_concat_view");
            }
        }
    }

    @Test
    void concatProvidesTextContextWithoutGloballyTypingBindsAsVarchar() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT CONCAT(?, '%') FROM DUAL")) {
            statement.setString(1, "abc");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("abc%", result.getString(1));
            }
        }

        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT CONCAT(?, '%') FROM DUAL")) {
            statement.setNull(1, Types.OTHER);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("%", result.getString(1));
            }
        }

        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT ? = DATE '2026-08-22' FROM DUAL")) {
            statement.setNull(1, Types.OTHER);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertNull(result.getObject(1));
            }
        }
    }

    @Test
    void scalarAndBindRewritesReachRealPostgres() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT ? || ':' || NULL, "
                                    + "DECODE(?, 1, 'one', 2, 'two', 'other') FROM DUAL")) {
                statement.setString(1, "A");
                statement.setInt(2, 2);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("A:", result.getString(1));
                    assertEquals("two", result.getString(2));
                    assertFalse(result.next());
                }
            }

            try (Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery(
                                    "SELECT ROUND(TO_DATE('2024-02-29 12:30:00', "
                                            + "'YYYY-MM-DD HH24:MI:SS'), 'HH24') FROM DUAL")) {
                assertTrue(result.next());
                assertEquals(
                        java.sql.Timestamp.valueOf("2024-02-29 13:00:00"), result.getTimestamp(1));
            }

            try (Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery(
                                    "SELECT LENGTH(TO_CHAR(SYSDATE, 'YYYYMMDD')), "
                                            + "LENGTH(TO_CHAR(SYSTIMESTAMP, "
                                            + "'YYYYMMDDHH24MISSFF6')) FROM DUAL")) {
                assertTrue(result.next());
                assertEquals(8, result.getInt(1));
                assertEquals(20, result.getInt(2));
            }
        }
    }

    @Test
    void boundedRoutineAdapterUsesCallableStatement() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute("DROP SCHEMA IF EXISTS orafit_junit_pkg CASCADE");
            setup.execute("CREATE SCHEMA orafit_junit_pkg");
            setup.execute(
                    "CREATE TABLE orafit_junit_pkg.call_log("
                            + "id integer generated always as identity, value text)");
            setup.execute(
                    "CREATE FUNCTION orafit_junit_pkg.lookup(p_value text) RETURNS text "
                            + "LANGUAGE sql IMMUTABLE AS $$ SELECT 'lookup:' || $1 $$");
            setup.execute(
                    "CREATE PROCEDURE orafit_junit_pkg.log_value(p_value text) "
                            + "LANGUAGE plpgsql AS $$ BEGIN "
                            + "INSERT INTO orafit_junit_pkg.call_log(value) VALUES (p_value); END $$");

            try (CallableStatement call =
                    connection.prepareCall("BEGIN ? := orafit_junit_pkg.lookup(?); END;")) {
                call.registerOutParameter(1, Types.VARCHAR);
                call.setString(2, "alpha");
                assertFalse(call.execute());
                assertEquals("lookup:alpha", call.getString(1));
                assertFalse(call.wasNull());
            }

            try (CallableStatement call =
                    connection.prepareCall(
                            "BEGIN orafit_junit_pkg.log_value("
                                    + "p_value => NVL(?, 'fallback')); END;")) {
                call.setNull(1, Types.VARCHAR);
                assertFalse(call.execute());
            }

            try (ResultSet result =
                    setup.executeQuery(
                            "SELECT value FROM orafit_junit_pkg.call_log "
                                    + "ORDER BY id DESC FETCH FIRST 1 ROW ONLY")) {
                assertTrue(result.next());
                assertEquals("fallback", result.getString(1));
            }
        }
    }

    @Test
    void delegatedTransactionsSavepointsAndBatchesRemainJdbcNative() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute("CREATE TEMP TABLE junit_tx(id integer primary key, value text)");
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert =
                        connection.prepareStatement("INSERT INTO junit_tx VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "keep");
                    assertEquals(1, insert.executeUpdate());
                }
                Savepoint savepoint = connection.setSavepoint();
                try (PreparedStatement insert =
                        connection.prepareStatement("INSERT INTO junit_tx VALUES (?, ?)")) {
                    insert.setInt(1, 2);
                    insert.setString(2, "drop");
                    assertEquals(1, insert.executeUpdate());
                }
                connection.rollback(savepoint);
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }

            try (PreparedStatement batch =
                    connection.prepareStatement(
                            "INSERT INTO junit_tx VALUES (?, NVL(?, 'fallback'))")) {
                batch.setInt(1, 3);
                batch.setString(2, "first");
                batch.addBatch();
                batch.setInt(1, 4);
                batch.setNull(2, Types.VARCHAR);
                batch.addBatch();
                int[] counts = batch.executeBatch();
                assertEquals(2, counts.length);
                assertTrue(counts[0] != Statement.EXECUTE_FAILED);
                assertTrue(counts[1] != Statement.EXECUTE_FAILED);
            }

            try (ResultSet result =
                    setup.executeQuery("SELECT id, value FROM junit_tx ORDER BY id")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
                assertEquals("first", result.getString(2));
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
                assertEquals("fallback", result.getString(2));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void structuralQueryRewritesComposeEndToEnd() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE junit_dept(id integer primary key, name text)");
            statement.execute(
                    "CREATE TEMP TABLE junit_emp("
                            + "id integer primary key, dept_id integer, name text)");
            statement.execute("INSERT INTO junit_dept VALUES (10, 'engineering')");
            statement.execute(
                    "INSERT INTO junit_emp VALUES " + "(1, 10, 'a'), (2, NULL, 'b'), (3, 99, 'c')");

            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT e.id, d.name FROM junit_emp e, junit_dept d "
                                    + "WHERE e.dept_id = d.id(+) ORDER BY e.id")) {
                int rows = 0;
                int missing = 0;
                while (result.next()) {
                    rows++;
                    if (result.getString(2) == null) {
                        missing++;
                    }
                }
                assertEquals(3, rows);
                assertEquals(2, missing);
            }

            try (PreparedStatement query =
                    connection.prepareStatement("SELECT id FROM junit_emp WHERE ROWNUM <= ?")) {
                query.setInt(1, 2);
                try (ResultSet result = query.executeQuery()) {
                    assertEquals(2, count(result));
                }
            }
        }
    }

    @Test
    void lnnvlTreatsFalseAndUnknownAsTrue() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute("CREATE TEMP TABLE junit_lnnvl(id integer, dept_id integer)");
            setup.execute("INSERT INTO junit_lnnvl VALUES (1, 10), (2, NULL), (3, 99)");
            try (PreparedStatement query =
                    connection.prepareStatement(
                            "SELECT count(*) FROM junit_lnnvl WHERE LNNVL(dept_id = ?)")) {
                query.setInt(1, 10);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
            }
        }
    }

    @Test
    void returningAndMergeUseJdbcAndDatabaseRuntimeTogether() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMP TABLE junit_generated("
                            + "id integer generated always as identity primary key, value text)");
            statement.execute("CREATE TEMP TABLE junit_target(id integer primary key, value text)");
            statement.execute("CREATE TEMP TABLE junit_source(id integer primary key, value text)");
            statement.execute("INSERT INTO junit_target VALUES (1, 'old')");
            statement.execute("INSERT INTO junit_source VALUES (1, 'new'), (2, 'inserted')");

            try (CallableStatement insert =
                    connection.prepareCall(
                            "INSERT INTO junit_generated(value) VALUES (?) "
                                    + "RETURNING id, value INTO ?, ?")) {
                insert.setString(1, "created");
                insert.registerOutParameter(2, Types.INTEGER);
                insert.registerOutParameter(3, Types.VARCHAR);
                assertEquals(1, insert.executeUpdate());
                assertEquals(1, insert.getInt(2));
                assertEquals("created", insert.getString(3));
            }

            statement.executeUpdate(
                    "MERGE INTO junit_target t USING junit_source s ON (t.id = s.id) "
                            + "WHEN MATCHED THEN UPDATE SET t.value = s.value "
                            + "WHEN NOT MATCHED THEN INSERT (id, value) VALUES (s.id, s.value)");
            try (ResultSet result =
                    statement.executeQuery("SELECT value FROM junit_target ORDER BY id")) {
                assertTrue(result.next());
                assertEquals("new", result.getString(1));
                assertTrue(result.next());
                assertEquals("inserted", result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void returningKeepsZeroRowOutStateAndKeywordColumns() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute(
                    "CREATE TEMP TABLE junit_returning("
                            + "id integer generated always as identity primary key, value text)");
            setup.execute(
                    "CREATE TEMP TABLE junit_returning_keyword("
                            + "id integer primary key, \"returning\" text)");
            setup.execute("INSERT INTO junit_returning_keyword VALUES (1, 'old')");

            try (CallableStatement update =
                    connection.prepareCall(
                            "UPDATE junit_returning SET value = ? WHERE id = -1 "
                                    + "RETURNING value INTO ?")) {
                update.setString(1, "never");
                update.registerOutParameter(2, Types.VARCHAR);
                assertEquals(0, update.executeUpdate());
                assertNull(update.getString(2));
                assertTrue(update.wasNull());
            }

            try (CallableStatement update =
                    connection.prepareCall(
                            "UPDATE junit_returning_keyword SET returning = ? WHERE id = ? "
                                    + "RETURNING id INTO ?")) {
                update.setString(1, "new");
                update.setInt(2, 1);
                update.registerOutParameter(3, Types.INTEGER);
                assertEquals(1, update.executeUpdate());
                assertEquals(1, update.getInt(3));
            }
            try (ResultSet result =
                    setup.executeQuery(
                            "SELECT \"returning\" FROM junit_returning_keyword WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals("new", result.getString(1));
            }
        }
    }

    @Test
    void oracleStyleErrorsRemainVisibleThroughJdbc() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE junit_listagg(id integer, value text)");
            statement.execute(
                    "INSERT INTO junit_listagg VALUES "
                            + "(1, repeat('x', 2500)), (2, repeat('y', 2500))");

            SQLException exception =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                try (ResultSet result =
                                        statement.executeQuery(
                                                "SELECT LISTAGG(value, ',' ON OVERFLOW ERROR) "
                                                        + "WITHIN GROUP (ORDER BY id) "
                                                        + "FROM junit_listagg")) {
                                    assertTrue(result.next());
                                    result.getString(1);
                                }
                            });
            assertEquals("72000", exception.getSQLState());
            assertTrue(exception.getMessage().contains("ORA-01489"));
        }

        // Use a fresh physical session after the deliberate server error.
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT q'[O'Reilly]', "
                                        + "REGEXP_LIKE('Abc', '^a', 'i') FROM DUAL")) {
            assertTrue(result.next());
            assertEquals("O'Reilly", result.getString(1));
            assertTrue(result.getBoolean(2));
        }
    }

    private static int count(ResultSet result) throws SQLException {
        int rows = 0;
        while (result.next()) {
            rows++;
        }
        return rows;
    }

    private static String inList(int size) {
        String expressions =
                IntStream.range(0, size).mapToObj(ignored -> "?").collect(Collectors.joining(","));
        return "SELECT 1 WHERE 1 IN (" + expressions + ")";
    }
}
