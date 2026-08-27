package io.github.orafit.parse;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.CallPlan;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Contract tests for the narrow single-routine PL/SQL call envelope. */
public final class CallEnvelopeCodecTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();

        Translation procedure = engine.translate("BEGIN payroll_pkg.open_employee(?, ?); END;");
        equal(CallPlan.Kind.PROCEDURE, procedure.call().kind(), "procedure call kind");
        equal("payroll_pkg.open_employee", procedure.call().routine(), "package routine mapping");
        equal(List.of(1, 2), procedure.binds().outputToInput(), "procedure bind lineage");
        check(
                procedure.sql().toUpperCase().startsWith("CALL PAYROLL_PKG.OPEN_EMPLOYEE"),
                "procedure must lower to CALL");

        Translation function = engine.translate("BEGIN ? := payroll_pkg.employee_name(?); END;");
        equal(CallPlan.Kind.FUNCTION, function.call().kind(), "function call kind");
        equal(1, function.call().returnParameterIndex(), "function return bind");
        equal(List.of(2), function.binds().outputToInput(), "function input bind lineage");
        check(
                function.sql().toUpperCase().startsWith("SELECT PAYROLL_PKG.EMPLOYEE_NAME"),
                "function must lower to SELECT");

        Translation schemaPackage = engine.translate("BEGIN hr.payroll_pkg.recalculate(?); END;");
        equal(
                "hr.payroll_pkg__recalculate",
                schemaPackage.call().routine(),
                "schema package mapping");

        Translation named =
                engine.translate(
                        "BEGIN payroll_pkg.record_run(p_when => SYSDATE, p_text => q'[O'Reilly]',"
                                + " p_id => ?); END;");
        String namedSql = named.sql().toLowerCase();
        check(
                namedSql.contains("p_when => orafit.sysdate()"),
                "named SYSDATE argument must share scalar lowering");
        check(
                namedSql.contains("p_text => 'o''reilly'"),
                "q quote must normalize before call parsing");
        equal(
                "p_id",
                named.call().arguments().get(2).name(),
                "named argument must remain in call plan");
        equal(
                1,
                named.call().arguments().get(2).directBindIndex(),
                "direct named bind must be identified");

        Translation scalar =
                engine.translate("BEGIN payroll_pkg.write(p_message => NVL(?, 'x')); END;");
        check(
                scalar.sql().toLowerCase().contains("orafit.nvl"),
                "CALL argument NVL must share function routing");
        equal(List.of(1), scalar.binds().outputToInput(), "nested scalar call bind lineage");

        unsupported(engine, "BEGIN payroll_pkg.run(p_id => ?, ?); END;", "PLSQL_NAMED_ARGUMENT");
        unsupported(engine, "BEGIN payroll_pkg.a(?); payroll_pkg.b(?); END;", "PLSQL_CALL");
        unsupported(engine, "DECLARE x NUMBER; BEGIN payroll_pkg.a(?); END;", "PLSQL_CALL");
        unsupported(engine, "BEGIN x := payroll_pkg.lookup(?); END;", "PLSQL_CALL");

        System.out.println("CallEnvelopeCodecTest OK");
    }

    private static void unsupported(OrafitEngine engine, String sql, String code) throws Exception {
        try {
            engine.translate(sql);
            throw new AssertionError("expected translation failure " + code + ": " + sql);
        } catch (TranslationException ex) {
            equal(code, ex.code(), "translation failure code");
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
}
