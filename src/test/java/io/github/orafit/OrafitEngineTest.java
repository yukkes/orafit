package io.github.orafit;

import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Dependency-free contract tests for the bounded compatibility engine. */
public final class OrafitEngineTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void fastPathAndLexicalDetection() throws Exception {

        Translation fast = engine.translate("select * from app_user where id = ?");
        equal(
                "select * from app_user where id = ?",
                fast.sql(),
                "ordinary SQL must be byte-for-byte pass-through");
        equal(List.of(1), fast.binds().outputToInput(), "fast-path bind lineage");
        check(!fast.rewritten(), "ordinary SQL must not be marked rewritten");

        Translation literal = engine.translate("select 'NVL(?) FROM DUAL' as text_value");
        check(
                !literal.rewritten(),
                "Oracle-looking text inside a literal must not trigger parsing");
        equal(0, literal.binds().inputCount(), "literal question mark must not become a bind");

        // Oracle treats a direct ORDER BY bind as the same constant value for every row. It does
        // not substitute the bound text or number as a column name or select-list ordinal.
        Translation orderByBind = engine.translate("SELECT id FROM items ORDER BY ?");
        equal(
                "SELECT id FROM items ORDER BY ?",
                orderByBind.sql(),
                "ORDER BY bind must remain a constant expression");
        equal(List.of(1), orderByBind.binds().outputToInput(), "ORDER BY bind lineage");
    }

    @Test
    void anonymousDmlBlockOwnsEachStatementAndItsBinds() throws Exception {
        Translation block =
                engine.translate(
                        "BEGIN "
                                + "INSERT INTO target_table(id, value) VALUES (?, ?); "
                                + "UPDATE target_table SET value = NVL(?, value) WHERE id = ?; "
                                + "END;");

        check(block.script().present(), "multiple DML statements must produce a script plan");
        equal(2, block.script().statements().size(), "script statement count");
        equal(
                List.of(1, 2),
                block.script().statements().get(0).binds().outputToInput(),
                "first statement bind lineage");
        equal(
                List.of(3, 4),
                block.script().statements().get(1).binds().outputToInput(),
                "second statement bind lineage");
        check(
                !block.sql().toUpperCase().contains("BEGIN"),
                "anonymous wrapper must not reach PostgreSQL");
    }

    @Test
    void scalarFunctions() throws Exception {
        Translation functions = engine.translate("SELECT NVL(?, 'x'), ADD_MONTHS(d, 1) FROM DUAL");
        String functionsSql = functions.sql().toLowerCase();
        check(functions.rewritten(), "Oracle functions/DUAL must be rewritten");
        check(functionsSql.contains("orafit.nvl"), "NVL must route to extension");
        check(functionsSql.contains("orafit.add_months"), "ADD_MONTHS must route to extension");
        check(functionsSql.contains("orafit.dual"), "DUAL must route to extension view");
        equal(
                List.of(1),
                functions.binds().outputToInput(),
                "function rewrite must preserve bind identity");

        Translation compactFunctions =
                engine.translate(
                        "SELECT SUBSTR('ABCDEFG', 0, 2), INSTR('ABC', 'B'), TRUNC(15.79, 1),"
                                + " ROUND(15.79, 1) FROM DUAL");
        String compactSql = compactFunctions.sql().toLowerCase();
        check(compactSql.contains("orafit.substr"), "SUBSTR must route to extension");
        check(compactSql.contains("orafit.instr"), "INSTR must route to extension");
        check(compactSql.contains("orafit.trunc"), "TRUNC must route to extension");
        check(compactSql.contains("orafit.round"), "ROUND must route to extension");

        Translation extremes =
                engine.translate("SELECT GREATEST(10, '20', ?, NULL), LEAST(10, 3, ?) FROM DUAL");
        String extremesSql = extremes.sql().toLowerCase();
        check(
                extremesSql.contains("orafit.greatest_number"),
                "numeric GREATEST must route to extension");
        check(extremesSql.contains("orafit.least_number"), "numeric LEAST must route to extension");
        equal(
                List.of(1, 2),
                extremes.binds().outputToInput(),
                "numeric extreme functions must preserve bind identity");

        Translation regexp =
                engine.translate(
                        "SELECT REGEXP_LIKE('Abc', '^a', 'i'), REGEXP_REPLACE('abc123', '([0-9]+)',"
                                + " 'X') FROM DUAL");
        String regexpSql = regexp.sql().toLowerCase();
        check(regexpSql.contains("orafit.regexp_like"), "REGEXP_LIKE must route to extension");
        check(
                regexpSql.contains("orafit.regexp_replace"),
                "REGEXP_REPLACE must route to extension");

        Translation lnnvl = engine.translate("SELECT id FROM emp WHERE LNNVL(amount >= ?)");
        String lnnvlSql = lnnvl.sql().toUpperCase();
        check(
                lnnvlSql.contains("COALESCE(NOT"),
                "LNNVL must preserve Oracle false-or-unknown semantics");
        check(lnnvlSql.contains("TRUE"), "LNNVL must map UNKNOWN to true");
        equal(List.of(1), lnnvl.binds().outputToInput(), "LNNVL must preserve bind identity");
    }

    @Test
    void likeTreatsBackslashAsLiteralWithoutOracleEscapeClause() throws Exception {
        Translation like =
                engine.translate("SELECT 1 FROM OC_MANAGE WHERE REQUEST_ID LIKE concat(?, '%')");
        check(
                like.sql().toLowerCase().contains("escape ''"),
                "Oracle LIKE must treat backslash as a literal unless ESCAPE is explicit");
        equal(List.of(1), like.binds().outputToInput(), "LIKE must preserve bind identity");
    }

    @Test
    void dateConcatSequenceAndDecode() throws Exception {
        Translation sysdate = engine.translate("SELECT SYSDATE FROM DUAL");
        String sysdateSql = sysdate.sql().toLowerCase();
        check(sysdateSql.contains("orafit.sysdate()"), "SYSDATE must route to extension runtime");
        check(sysdateSql.contains("orafit.dual"), "SYSDATE query must still route DUAL");
        Translation sysdateArithmetic = engine.translate("SELECT SYSDATE + 1 FROM DUAL");
        String sysdateArithmeticSql = sysdateArithmetic.sql().toLowerCase();
        check(
                sysdateArithmeticSql.contains("orafit.sysdate()"),
                "SYSDATE arithmetic must route to extension runtime");
        check(
                sysdateArithmeticSql.contains("orafit.days_interval"),
                "SYSDATE numeric arithmetic must use day intervals");

        Translation concat = engine.translate("SELECT ? || ':' || NULL FROM DUAL");
        String concatSql = concat.sql().toLowerCase();
        check(
                concatSql.contains("orafit.concat_varchar2"),
                "Oracle concat must route to extension semantics");
        check(
                concatSql.contains("?::text"),
                "bind concat operands must receive local text context");
        check(!concatSql.contains(" || "), "Oracle concat operator must not reach PostgreSQL");
        equal(List.of(1), concat.binds().outputToInput(), "concat must preserve bind identity");

        Translation concatFunction = engine.translate("SELECT CONCAT(?, '%') FROM DUAL");
        String concatFunctionSql = concatFunction.sql().toLowerCase();
        check(
                concatFunctionSql.contains("orafit.concat_varchar2"),
                "Oracle CONCAT function must route to extension semantics");
        check(
                concatFunctionSql.contains("?::text"),
                "Oracle CONCAT function bind must receive local text context");

        Translation sequence = engine.translate("SELECT emp_seq.NEXTVAL FROM DUAL");
        check(
                sequence.sql().toLowerCase().contains("pg_catalog.nextval('emp_seq')"),
                "Oracle NEXTVAL must lower to PostgreSQL sequence runtime");
        Translation sequenceInsert =
                engine.translate(
                        "INSERT INTO t(id, parent_id) VALUES (app.seq.NEXTVAL, seq2.CURRVAL)");
        String sequenceInsertSql = sequenceInsert.sql().toLowerCase();
        check(
                sequenceInsertSql.contains("pg_catalog.nextval('app.seq')"),
                "qualified NEXTVAL must preserve sequence name");
        check(
                sequenceInsertSql.contains("pg_catalog.currval('seq2')"),
                "CURRVAL must lower to PostgreSQL sequence runtime");

        Translation bareRestart = engine.translate("ALTER SEQUENCE app.seq RESTART");
        check(
                bareRestart.sql().contains("orafit.restart_sequence('app.seq'::regclass)"),
                "bare RESTART must restart at the sequence MINVALUE");
        Translation explicitRestart =
                engine.translate("ALTER SEQUENCE app.seq RESTART START WITH 7");
        equal(
                "ALTER SEQUENCE app.seq RESTART WITH 7",
                explicitRestart.sql(),
                "explicit restart value must remain unchanged");

        Translation currentTime =
                engine.translate(
                        "SELECT TO_CHAR(SYSDATE, 'YYYYMMDD'), "
                                + "TO_CHAR(SYSTIMESTAMP, 'HH24MISSFF6') FROM DUAL");
        String currentTimeSql = currentTime.sql().toLowerCase();
        check(
                currentTimeSql.contains("orafit.to_char_format(orafit.sysdate()"),
                "TO_CHAR(SYSDATE, format) must use the date compatibility overload");
        check(
                currentTimeSql.contains("orafit.to_char_format(orafit.systimestamp()"),
                "TO_CHAR(SYSTIMESTAMP, format) must use the timestamp compatibility overload");

        Translation decodeText =
                engine.translate(
                        "SELECT DECODE(?, 'A', 'Active', 'I', 'Inactive', 'Unknown') FROM DUAL");
        String decodeTextSql = decodeText.sql().toUpperCase();
        check(decodeTextSql.contains("CASE"), "DECODE must lower to CASE");
        check(
                decodeTextSql.contains("IS NOT DISTINCT FROM"),
                "DECODE must preserve Oracle NULL equality");
        equal(
                List.of(1, 1),
                decodeText.binds().outputToInput(),
                "DECODE comparisons may duplicate one scalar bind safely");

        Translation decodeNull =
                engine.translate("SELECT DECODE(name, 'A', '', NULL, 'missing', 'other') FROM emp");
        String decodeNullSql = decodeNull.sql().toUpperCase();
        check(
                decodeNullSql.contains("THEN NULL"),
                "Oracle empty-string DECODE result must normalize to NULL");
        check(
                decodeNullSql.contains("IS NOT DISTINCT FROM NULL"),
                "DECODE NULL search must match NULL source");

        Translation decodeNumber =
                engine.translate("SELECT DECODE(?, 1, 10, 2, -20, NULL, 30, 0) FROM DUAL");
        check(
                decodeNumber.sql().toLowerCase().contains("orafit.to_number_bind"),
                "numeric DECODE bind comparison must delegate conversion to extension");
        equal(
                List.of(1, 1, 1),
                decodeNumber.binds().outputToInput(),
                "numeric DECODE must preserve duplicated bind lineage");
    }

    @Test
    void createViewRewritesOracleExpressionsInsideItsQuery() throws Exception {
        Translation view =
                engine.translate(
                        "CREATE VIEW employee_names AS "
                                + "SELECT first_name || middle_name || last_name AS full_name "
                                + "FROM DUAL");
        String viewSql = view.sql().toLowerCase();

        check(view.rewritten(), "Oracle expressions inside CREATE VIEW must be rewritten");
        check(
                viewSql.contains("orafit.concat_varchar2"),
                "view concatenation must preserve Oracle NULL semantics");
        check(
                !viewSql.contains(" || "),
                "Oracle concat operator inside a view must not reach PostgreSQL");
        check(
                viewSql.contains("orafit.dual"),
                "DUAL inside a view must route to the extension view");
    }

    @Test
    void lexicalAndSimpleSyntax() throws Exception {
        Translation qualified =
                engine.translate("SELECT app.substr(name, 1), app.round(amount) FROM emp");
        check(!qualified.rewritten(), "qualified application functions must stay on the fast path");

        Translation unique = engine.translate("SELECT UNIQUE deptno FROM emp");
        check(unique.rewritten(), "SELECT UNIQUE must be rewritten");
        check(
                unique.sql().toUpperCase().contains("SELECT DISTINCT"),
                "SELECT UNIQUE must become DISTINCT");

        Translation qquote = engine.translate("INSERT INTO t(v) VALUES (q'[O'Reilly]')");
        check(qquote.rewritten(), "Oracle q-quoted literal must be normalized");
        check(qquote.sql().contains("'O''Reilly'"), "q-quoted apostrophe must be escaped");

        Translation nqquote = engine.translate("SELECT nq'{日本語 ' text}' AS value FROM DUAL");
        check(nqquote.rewritten(), "Oracle nq-quoted literal must be normalized");
        check(nqquote.sql().contains("'日本語 '' text'"), "nq-quoted literal must preserve content");

        Translation hint = engine.translate("SELECT /*+ INDEX(t idx_t) */ id FROM t WHERE id = ?");
        check(hint.rewritten(), "Oracle optimizer hint must be normalized");
        check(!hint.sql().contains("/*+"), "Oracle optimizer hint must not reach PostgreSQL");
        equal(
                List.of(1),
                hint.binds().outputToInput(),
                "hint normalization must preserve bind identity");

        Translation toDate = engine.translate("SELECT TO_DATE(?, 'YYYY-MM-DD') FROM DUAL");
        check(
                toDate.sql().toLowerCase().contains("orafit.to_date"),
                "safe TO_DATE format must be routed");
        equal(List.of(1), toDate.binds().outputToInput(), "TO_DATE bind identity");

        Translation fullwidth =
                engine.translate(
                        "SELECT \"列（＝）\" FROM t WHERE id＝1 AND code IN（'A'） "
                                + "AND note = '（＝）' /* （＝） */");
        equal(
                "SELECT \"列（＝）\" FROM t WHERE id=1 AND code IN('A') "
                        + "AND note = '（＝）' /* （＝） */",
                fullwidth.sql(),
                "fullwidth SQL punctuation must normalize only outside quoted content");
        check(fullwidth.rewritten(), "fullwidth SQL punctuation must be marked rewritten");
    }

    @Test
    void rownumAndOuterJoin() throws Exception {
        Translation rownum =
                engine.translate("SELECT id FROM app_user WHERE status = ? AND ROWNUM <= 10");
        String rownumSql = rownum.sql().toUpperCase();
        check(
                rownumSql.contains("FETCH FIRST 10 ROWS ONLY"),
                "ROWNUM <= literal must become FETCH FIRST");
        check(!rownumSql.contains("ROWNUM"), "rewritten block must not retain ROWNUM");
        equal(
                List.of(1),
                rownum.binds().outputToInput(),
                "ROWNUM rewrite must preserve other binds");

        Translation rownumFirst =
                engine.translate("SELECT id FROM app_user WHERE ROWNUM < 5 AND status = ?");
        check(
                rownumFirst.sql().toUpperCase().contains("FETCH FIRST 4 ROWS ONLY"),
                "ROWNUM < literal must use prefix cardinality");
        equal(
                List.of(1),
                rownumFirst.binds().outputToInput(),
                "predicate removal must not reorder remaining binds");

        Translation outer =
                engine.translate(
                        "SELECT e.empno, d.dname FROM emp e, dept d WHERE e.deptno = d.deptno(+)");
        String outerSql = outer.sql().toUpperCase();
        check(outer.rewritten(), "Oracle (+) must be rewritten");
        check(outerSql.contains("LEFT JOIN DEPT D"), "Oracle (+) must become LEFT JOIN");
        check(!outerSql.contains("(+)"), "rewritten outer join must not retain (+)");

        Translation multipleParents =
                engine.translate(
                        "SELECT e.empno, l.location_id, d.dname"
                                + " FROM bs_emp e, bs_location l, bs_dept d"
                                + " WHERE e.deptno = d.deptno(+)"
                                + " AND l.location_id = d.location_id(+)"
                                + " ORDER BY e.empno, l.location_id");
        String multipleParentsSql = multipleParents.sql().toUpperCase();
        check(
                multipleParentsSql.contains("CROSS JOIN BS_LOCATION L"),
                "multiple-parent outer join must group mandatory relations explicitly");
        check(
                multipleParentsSql.contains("LEFT JOIN BS_DEPT D"),
                "multiple-parent outer join must retain the optional relation as LEFT JOIN");
        check(
                multipleParentsSql.contains(
                        "ON E.DEPTNO = D.DEPTNO AND L.LOCATION_ID = D.LOCATION_ID"),
                "multiple-parent outer join conditions must stay on the LEFT JOIN");
    }

    @Test
    void dmlAndReturning() throws Exception {
        Translation merge =
                engine.translate(
                        "MERGE INTO target t USING source s ON (t.id = s.id) WHEN MATCHED THEN"
                                + " UPDATE SET t.value = s.value WHEN NOT MATCHED THEN INSERT (id,"
                                + " value) VALUES (s.id, s.value)");
        String mergeSql = merge.sql().toUpperCase();
        check(merge.rewritten(), "Oracle MERGE must be normalized");
        check(mergeSql.startsWith("MERGE INTO"), "MERGE must remain PostgreSQL MERGE");
        check(!mergeSql.contains("SET T.VALUE"), "MERGE target qualifier must be removed from SET");

        Translation returning =
                engine.translate(
                        "INSERT INTO t(v) VALUES (?) RETURNING id, NVL(v, 'x') INTO ?, ?;");
        String returningSql = returning.sql().toUpperCase();
        check(returning.rewritten(), "RETURNING INTO must be normalized");
        check(
                returningSql.indexOf(" INTO ", returningSql.indexOf("RETURNING")) < 0,
                "RETURNING INTO targets must leave PostgreSQL SQL");
        check(
                returning.sql().toLowerCase().contains("orafit.nvl"),
                "RETURNING expression rewrites share the same AST pass");
        equal(3, returning.binds().inputCount(), "RETURNING keeps original JDBC parameter count");
        equal(
                List.of(1),
                returning.binds().outputToInput(),
                "only input binds remain in PostgreSQL SQL");
        check(
                returning.returning().isOutput(2) && returning.returning().isOutput(3),
                "RETURNING OUT positions must be planned");
    }

    @Test
    void rejectsUnsupportedForms() throws Exception {
        unsupported(
                engine,
                "SELECT id FROM t WHERE ROWNUM <= 10 ORDER BY created_at",
                "ROWNUM_ORDER_BY");
        unsupported(engine, "SELECT NVL(a) FROM DUAL", "FUNCTION_NVL");
        unsupported(engine, "SELECT LNNVL(a = 1, b = 2) FROM t", "FUNCTION_LNNVL");
        unsupported(engine, "SELECT DECODE(status, other_column, 1, 0) FROM t", "DECODE");
        unsupported(engine, "SELECT DECODE(status, 'A', 1, 2, 2, 0) FROM t", "DECODE");
        unsupported(engine, "SELECT GREATEST('a', 'b') FROM DUAL", "FUNCTION_GREATEST_TYPE");
        unsupported(engine, "SELECT :__orafit_b1 FROM t", "UNSUPPORTED_ORACLE");
        unsupported(engine, "SELECT __ocp_0123456789abcdef_000001__ FROM t", "UNSUPPORTED_ORACLE");
        unsupported(engine, "INSERT INTO t(v) VALUES (?) RETURNING id, v INTO ?", "RETURNING_INTO");
        unsupported(
                engine, "SELECT TO_DATE(?, 'MONTH DD YYYY') FROM DUAL", "FUNCTION_TO_DATE_FORMAT");
        unsupported(engine, "SELECT REGEXP_LIKE('a', 'a', 'x') FROM DUAL", "REGEXP_MATCH_PARAM");
        unsupported(engine, "SELECT REGEXP_LIKE('a', ?) FROM DUAL", "REGEXP_PATTERN");
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
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
