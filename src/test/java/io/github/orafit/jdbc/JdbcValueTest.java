package io.github.orafit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;

/** Oracle NUMBER normalization checks for synthetic JDBC OUT values. */
final class JdbcValueTest {
    @Test
    void normalizesInsignificantScaleAcrossCallableGetters() throws Exception {
        BigDecimal stored = new BigDecimal("967.74100000");
        BigDecimal expected = new BigDecimal("967.741");

        assertEquals(expected, get(stored, "getBigDecimal", int.class));
        assertEquals(expected, get(stored, "getObject", int.class));
        assertEquals(expected, get(stored, "getObject", int.class, Class.class));
        assertEquals("967.741", get(stored, "getString", int.class));
    }

    @Test
    void preservesSignificantFractionalDigits() throws Exception {
        BigDecimal expression = new BigDecimal("967.740999999999");

        assertEquals(expression, get(expression, "getBigDecimal", int.class));
        assertEquals("967.740999999999", get(expression, "getString", int.class));
    }

    private static Object get(Object value, String name, Class<?>... parameterTypes)
            throws Exception {
        Method method = CallableStatement.class.getMethod(name, parameterTypes);
        Object[] args =
                parameterTypes.length == 2 ? new Object[] {1, BigDecimal.class} : new Object[] {1};
        return JdbcValue.get(value, method, args, "test OUT");
    }
}
