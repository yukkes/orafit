package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

final class ScalarCompatibilityPostgresTest {
    @Test
    void replaceRetainsBindOrderAndCanBeReboundToNull() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT REPLACE(?, ?, ?) FROM dual")) {
            assertEquals(3, statement.getParameterMetaData().getParameterCount());
            statement.setString(1, "aba");
            statement.setString(2, "a");
            statement.setString(3, "XX");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("XXbXX", result.getString(1));
            }
            statement.setNull(3, Types.VARCHAR);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("b", result.getString(1));
            }
        }
    }

    @Test
    void invalidNullFunctionArgumentsPreserveOracleErrors() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            SQLException coalesce =
                    assertThrows(
                            SQLException.class,
                            () -> statement.executeQuery("SELECT COALESCE(1) FROM dual"));
            assertEquals(938, coalesce.getErrorCode());
            assertEquals("42000", coalesce.getSQLState());
            SQLException nullif =
                    assertThrows(
                            SQLException.class,
                            () -> statement.executeQuery("SELECT NULLIF(NULL, 1) FROM dual"));
            assertEquals(932, nullif.getErrorCode());
            assertEquals("42000", nullif.getSQLState());
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    REPLACE('abc', NULL, 'x') | abc | 3
                    REPLACE('aba', 'a', NULL) | b | 1
                    REPLACE('aba', 'a') | b | 1
                    REPLACE('abc', 'b', 'XX') | aXXc | 4
                    TO_CHAR(0.1) | .1 | 2
                    TO_CHAR(-0.1) | -.1 | 3
                    UPPER(123) | 123 | 3
                    RPAD('a', 3.9, 'x') | axx | 3
                    """)
    void scalarValuesAndMetadataMatchOracle(String expression, String expected, int precision)
            throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " AS value FROM dual")) {
            assertTrue(result.next());
            assertEquals(expected, result.getString(1));
            assertEquals(precision, result.getMetaData().getPrecision(1));
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    REPLACE('a', 'a') | 1
                    REPLACE('a', 'a', '') | 1
                    TRANSLATE('a', 'xa', 'x') | 1
                    TRANSLATE('abc', 'a', '') | 1
                    """)
    void stringDeletionProducesSqlNull(String expression, int expected) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT CASE WHEN "
                                        + expression
                                        + " IS NULL THEN 1 ELSE 0 END AS value FROM dual")) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
        }
    }
}
