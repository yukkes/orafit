package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

final class NumberFunctionPostgresTest {
    @ParameterizedTest
    @ValueSource(strings = {"MOD(7, 0)", "MOD('7', '3')", "MOD(-7.5, 2)"})
    void literalModPreservesOracleNumericMetadata(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " AS value FROM dual")) {
            assertEquals(Types.NUMERIC, result.getMetaData().getColumnType(1));
            assertEquals(0, result.getMetaData().getPrecision(1));
            assertEquals(-127, result.getMetaData().getScale(1));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1,234",
                "0x10",
                "0b10",
                "0o10",
                "1_234",
                "NaN",
                "Infinity",
                "   ",
                "\t12\t",
                "\n12\n"
            })
    void invalidDecimalTextPreservesOracleErrorAndAllowsStatementReuse(String input)
            throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT TO_NUMBER(?) AS value FROM dual")) {
            connection.setAutoCommit(false);
            statement.setString(1, input);
            SQLException error = assertThrows(SQLException.class, statement::executeQuery);
            assertEquals(1722, error.getErrorCode());
            assertEquals("42000", error.getSQLState());

            statement.setString(1, " +12.5e-1 ");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, new BigDecimal("1.25").compareTo(result.getBigDecimal(1)));
                assertFalse(result.next());
            }
            connection.rollback();
        }
    }

    @Test
    void modPreservesZeroDivisorSignsNullsAndReboundNumericArguments() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT MOD(?, ?) AS value FROM dual")) {
            String[][] inputs = {
                {"7", "0", "7"}, {"-7", "3", "-1"}, {"7", "-3", "1"}, {"7.5", "2", "1.5"}
            };
            for (String[] input : inputs) {
                statement.setString(1, input[0]);
                statement.setString(2, input[1]);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, new BigDecimal(input[2]).compareTo(result.getBigDecimal(1)));
                    assertFalse(result.next());
                }
            }
            statement.setInt(1, 7);
            statement.setNull(2, Types.NUMERIC);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertNull(result.getBigDecimal(1));
                assertTrue(result.wasNull());
            }
        }
    }
}
