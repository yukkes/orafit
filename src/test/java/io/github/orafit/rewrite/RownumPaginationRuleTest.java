package io.github.orafit.rewrite;

import io.github.orafit.parse.BindCodec;
import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.parse.SqlGate;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.statement.Statement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** Contracts for the classic Oracle ROWNUM projection pagination lowering. */
public final class RownumPaginationRuleTest {
    @Test
    void contract() throws Exception {
        Result ordered =
                translate(
                        "SELECT * FROM (SELECT a.*, ROWNUM rn FROM (SELECT id, name FROM emp ORDER"
                                + " BY id) a WHERE ROWNUM <= ?) page WHERE page.rn > ?");
        check(
                ordered.sql().contains("ROW_NUMBER() OVER (ORDER BY id) rn"),
                "source order must drive ROW_NUMBER");
        check(
                ordered.sql().contains("FETCH FIRST CAST("),
                "bind upper bound must become dynamic FETCH");
        equal(List.of(1, 2), ordered.lineage(), "pagination should not duplicate JDBC binds");

        Result literal =
                translate(
                        "SELECT * FROM (SELECT a.*, ROWNUM rn FROM emp a WHERE ROWNUM <= 3.7) p "
                                + "WHERE p.rn > -2.5");
        check(
                literal.sql().contains("ROW_NUMBER() OVER () rn"),
                "unordered source uses an unordered row number");
        check(
                literal.sql().contains("FETCH FIRST 3 ROWS ONLY"),
                "fractional literal upper bound must floor");

        unsupported(
                "SELECT * FROM (SELECT a.*, ROWNUM rn FROM emp a "
                        + "WHERE active = ? AND ROWNUM < ?) p WHERE p.rn > ?");
        System.out.println("RownumPaginationRuleTest OK");
    }

    private static Result translate(String sql) throws Exception {
        SqlGate gate = new SqlGate();
        SqlGate.Scan scan = gate.scan(sql);
        BindCodec binds = new BindCodec();
        BindCodec.Encoded encoded = binds.encode(scan.sql(), scan);
        Statement statement = new ParserAdapter().parse(encoded.sql());
        check(new RownumRule().rewrite(statement).changed(), "pagination rule must rewrite");
        BindCodec.Decoded decoded =
                binds.decode(statement.toString(), encoded.bindCount(), Set.of());
        return new Result(decoded.sql(), decoded.lineage().outputToInput());
    }

    private static void unsupported(String sql) throws Exception {
        try {
            translate(sql);
            throw new AssertionError("expected ROWNUM_NESTED_UPPER");
        } catch (TranslationException ex) {
            equal("ROWNUM_NESTED_UPPER", ex.code(), "failure code");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private record Result(String sql, List<Integer> lineage) {}
}
