package io.github.orafit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/** Regression coverage for Oracle TRIM/LTRIM/RTRIM translation. */
public final class TrimFunctionTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void trimFamilyRoutesThroughOracleStringRuntime() throws Exception {
        Translation trim = engine.translate("SELECT TRIM(code) AS value FROM accounts");
        assertTrue(trim.sql().toLowerCase().contains("orafit.trim"));

        Translation ltrim = engine.translate("SELECT LTRIM(code, '0') AS value FROM accounts");
        String ltrimSql = ltrim.sql().toLowerCase();
        assertTrue(ltrimSql.contains("orafit.ltrim"));
        assertTrue(ltrimSql.contains("::text"));

        Translation rtrim = engine.translate("SELECT RTRIM(code, '0') AS value FROM accounts");
        String rtrimSql = rtrim.sql().toLowerCase();
        assertTrue(rtrimSql.contains("orafit.rtrim"));
        assertTrue(rtrimSql.contains("::text"));
    }

    @Test
    void trimDirectionAndCharacterArePreserved() throws Exception {
        Translation translation =
                engine.translate("SELECT TRIM(LEADING '0' FROM code) AS value FROM accounts");
        String sql = translation.sql().toLowerCase();
        assertTrue(sql.contains("orafit.trim"));
        assertTrue(sql.contains("'leading'"));
        assertTrue(sql.contains("'0'::text"));
    }

    @Test
    void simpleTrimKeepsOracleFacingExpressionLabel() throws Exception {
        Translation translation = engine.translate("SELECT TRIM(code) FROM accounts");
        assertEquals("TRIM(CODE)", translation.metadata().column(1).label());
    }
}
