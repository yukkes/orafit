package io.github.orafit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.parse.SqlGate;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Regression coverage for generic Oracle SQL lexical and structural compatibility. */
final class SqlCompatibilityRegressionTest {
    @Test
    void normalizesOracleFullWidthWhitespaceOutsideLiteralsAndIdentifiers() throws Exception {
        Translation translated =
                new OrafitEngine()
                        .translate(
                                "SELECT\u3000/*+ gather_plan_statistics INDEX(C IDX_C) */ "
                                        + "C.ID FROM CUSTOMER C WHERE C.ID = ?");

        assertFalse(translated.sql().contains("\u3000"));
        assertFalse(translated.sql().contains("/*+"));
    }

    @Test
    void preservesFullWidthSpacesInsideLiteralsAndQuotedIdentifiers() throws Exception {
        Translation translated =
                new OrafitEngine().translate("SELECT 'A\u3000B' AS \"C\u3000D\" FROM DUAL");

        assertTrue(translated.sql().contains("'A\u3000B'"));
        assertTrue(translated.sql().contains("\"C\u3000D\""));
    }

    @Test
    void separatesJdbcBindFromFollowingKeyword() throws Exception {
        Translation translated =
                new OrafitEngine()
                        .translate(
                                "SELECT MIN(ID) FROM CUSTOMER "
                                        + "WHERE START_DATE <= ?AND END_DATE > ?OR ID = ?");

        assertTrue(translated.sql().contains("? AND"));
        assertTrue(translated.sql().contains("? OR"));
        assertEquals(3, translated.binds().inputCount());
    }

    @Test
    void leavesNonKeywordBindSuffixesAndLexicallyProtectedTextAlone() throws Exception {
        SqlGate.Scan scan =
                new SqlGate()
                        .scan(
                                "SELECT '?AND', q'[?OR]', ?foo, ?1, ?ABC123 FROM DUAL "
                                        + "-- ?AND\nWHERE ?AND 1 = 1 /* ?OR */ OR ?OR 2 = 2");

        assertTrue(scan.sql().contains("'?AND'"));
        assertTrue(scan.sql().contains("'?OR'"));
        assertTrue(scan.sql().contains("?foo, ?1, ?ABC123"));
        assertTrue(scan.sql().contains("-- ?AND"));
        assertTrue(scan.sql().contains("/* ?OR */"));
        assertTrue(scan.sql().contains("WHERE ? AND"));
        assertTrue(scan.sql().contains("OR ? OR"));
        assertEquals(5, scan.bindOffsets().size());
    }

    @Test
    void acceptsCteWithNestedInlineViewAndOneThousandInValues() throws Exception {
        Translation translated = new OrafitEngine().translate(complexInList(1000));

        assertEquals(2000, translated.binds().inputCount());
    }

    @Test
    void preservesParenthesizedSetOperandsBeyondParserLookaheadLimit() throws Exception {
        Select parsed =
                assertInstanceOf(Select.class, new ParserAdapter().parse(complexInList(1000)));
        ParenthesedSelect inlineView =
                assertInstanceOf(ParenthesedSelect.class, parsed.getPlainSelect().getFromItem());
        SetOperationList union = inlineView.getSetOperationList();

        assertEquals(2, union.getSelects().size());
        assertInstanceOf(ParenthesedSelect.class, union.getSelect(0));
        assertInstanceOf(ParenthesedSelect.class, union.getSelect(1));
    }

    @Test
    void reportsOracleInListLimitAfterParsingTheSameComplexShape() {
        TranslationException exception =
                assertThrows(
                        TranslationException.class,
                        () -> new OrafitEngine().translate(complexInList(1001)));

        assertEquals("IN_LIST_LIMIT", exception.code());
    }

    private static String complexInList(int size) {
        String values =
                IntStream.range(0, size).mapToObj(ignored -> "?").collect(Collectors.joining(", "));
        return "WITH SUB_RUK AS (SELECT ID FROM RUK) "
                + "SELECT Q_TARGET.ID FROM ((SELECT RSC.ID FROM RSC "
                + "WHERE (RSC.MANAGEMENT_NO) IN ("
                + values
                + ")) UNION (SELECT PREVIOUS_RSC.ID FROM PREVIOUS_RSC "
                + "JOIN SUB_RUK ON SUB_RUK.ID = PREVIOUS_RSC.ID "
                + "WHERE PREVIOUS_RSC.MANAGEMENT_NO IN ("
                + values
                + "))) Q_TARGET";
    }
}
