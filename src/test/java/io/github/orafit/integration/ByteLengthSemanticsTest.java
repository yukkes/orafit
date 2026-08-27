package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Execution(ExecutionMode.SAME_THREAD)
final class ByteLengthSemanticsTest {
    @Test
    void appliesOracleByteLimitsToBoundedCharacterColumns() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS orafit_byte_test CASCADE");
            statement.execute("CREATE SCHEMA orafit_byte_test");
            try {
                statement.execute(
                        "CREATE TABLE orafit_byte_test.values_under_test ("
                                + "id integer PRIMARY KEY, variable_value varchar(3), "
                                + "fixed_value char(3), unlimited_value varchar)");

                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT orafit.apply_byte_length_semantics("
                                        + "'orafit_byte_test')")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT orafit.apply_byte_length_semantics("
                                        + "'orafit_byte_test')")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }

                statement.execute(
                        "INSERT INTO orafit_byte_test.values_under_test "
                                + "VALUES (1, 'あ', 'あ', 'ああ')");
                assertOracleTooLarge(
                        assertThrows(
                                SQLException.class,
                                () ->
                                        statement.execute(
                                                "INSERT INTO orafit_byte_test.values_under_test "
                                                        + "VALUES (2, 'ああ', 'A', NULL)")));
                assertOracleTooLarge(
                        assertThrows(
                                SQLException.class,
                                () ->
                                        statement.execute(
                                                "INSERT INTO orafit_byte_test.values_under_test "
                                                        + "VALUES (3, 'A', 'ああ', NULL)")));
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS orafit_byte_test CASCADE");
            }
        }
    }

    private static void assertOracleTooLarge(SQLException failure) {
        assertEquals(12899, failure.getErrorCode());
        assertEquals("72000", failure.getSQLState());
        assertTrue(failure.getMessage().startsWith("ORA-12899:"));
    }
}
