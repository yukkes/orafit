package io.github.orafit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Regression coverage for the shared Oracle padding-function coercion contract. */
public final class PaddingFunctionTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void lpadAndRpadUseTypedCompatibilityArguments() throws Exception {
        Translation lpad = engine.translate("SELECT LPAD(ename, 8, '0') FROM bs_emp");
        String lpadSql = lpad.sql().toLowerCase();
        assertTrue(lpad.rewritten());
        assertTrue(lpadSql.contains("orafit.lpad"));
        assertTrue(lpadSql.contains("::numeric"));
        assertTrue(lpadSql.contains("::text"));

        Translation rpad = engine.translate("SELECT RPAD(ename, 8, '0') FROM bs_emp");
        String rpadSql = rpad.sql().toLowerCase();
        assertTrue(rpad.rewritten());
        assertTrue(rpadSql.contains("orafit.rpad"));
        assertTrue(rpadSql.contains("::numeric"));
        assertTrue(rpadSql.contains("::text"));
    }

    @Test
    void paddingBindsPreserveLineageThroughSharedCoercion() throws Exception {
        Translation rpad = engine.translate("SELECT RPAD(?, ?, ?) FROM bs_emp");
        String sql = rpad.sql().toLowerCase();
        assertTrue(sql.contains("orafit.rpad"));
        assertTrue(sql.contains("?::text"));
        assertTrue(sql.contains("orafit.to_number"));
        assertEquals(List.of(1, 2, 3), rpad.binds().outputToInput());
    }
}
