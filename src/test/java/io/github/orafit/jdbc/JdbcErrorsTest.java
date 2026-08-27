package io.github.orafit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

final class JdbcErrorsTest {
    @Test
    void mapsOnlyOrafitByteLengthCheckViolationsToOra12899() {
        SQLException postgres =
                new SQLException(
                        "new row violates check constraint \"orafit_byte_length_012345\"", "23514");

        SQLException oracle = JdbcErrors.fromDelegate(postgres);

        assertEquals(12899, oracle.getErrorCode());
        assertEquals("72000", oracle.getSQLState());
        assertTrue(oracle.getMessage().startsWith("ORA-12899:"));
        assertSame(postgres, oracle.getCause());
    }

    @Test
    void leavesUnrelatedCheckViolationsUnchanged() {
        SQLException postgres =
                new SQLException("new row violates check constraint \"positive_amount\"", "23514");

        assertSame(postgres, JdbcErrors.fromDelegate(postgres));
    }
}
