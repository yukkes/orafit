package io.github.orafit.jdbc;

import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;
import io.github.orafit.translation.ResultMetadataPlan.Source;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/** Database-free checks for runtime Oracle metadata overlays. */
public final class JdbcMetadataTest {
    @Test
    void resolvesOracleCountLabelWithoutBreakingStringAccess() throws Exception {
        ResultSetMetaData metadata = metadata(Types.NUMERIC, "numeric", 0, 0);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> {
                                        equal("count", args[0], "PostgreSQL aggregate label");
                                        yield "2";
                                    }
                                    case "findColumn" -> {
                                        if (!"count".equals(args[0])) {
                                            throw new SQLException("unknown column " + args[0]);
                                        }
                                        yield 1;
                                    }
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("COUNT(*)", Kind.AUTO, 0, 0)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("2", result.getString("COUNT(*)"), "Oracle aggregate label string access");
    }

    @Test
    void normalizesNumberValuesConsistentlyAcrossJdbcGetters() throws Exception {
        ResultSetMetaData metadata = metadata(Types.NUMERIC, "numeric", 15, 3);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getBigDecimal", "getObject" ->
                                            new BigDecimal("2049.32000000002");
                                    case "getString" -> "2049.32000000002";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("VALUE", Kind.NUMBER, 15, 3)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        BigDecimal expected = new BigDecimal("2049.32");
        equal(expected, result.getBigDecimal(1), "getBigDecimal NUMBER value");
        equal(expected, result.getObject(1), "getObject NUMBER value");
        equal(expected, result.getObject(1, BigDecimal.class), "typed getObject NUMBER value");
        equal("2049.32", result.getString(1), "getString NUMBER value");
    }

    @Test
    void preservesExplicitLabelsContainingParentheses() throws Exception {
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("findColumn")) {
                                if (!"A (B)".equals(args[0])) {
                                    throw new SQLException("unknown column " + args[0]);
                                }
                                return 1;
                            }
                            return defaultValue(method.getReturnType());
                        });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("A (B)", Kind.AUTO, null, null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal(1, result.findColumn("A (B)"), "explicit parenthesized label");
    }

    @Test
    void preservesExplicitCountShapedLabel() throws Exception {
        ResultSetMetaData metadata =
                JdbcProxy.proxy(
                        ResultSetMetaData.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getColumnCount" -> 1;
                                    case "getColumnLabel" -> "COUNT(*)";
                                    case "getColumnType" -> Types.NUMERIC;
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> {
                                        equal("COUNT(*)", args[0], "explicit aggregate alias");
                                        yield "2";
                                    }
                                    case "findColumn" -> "COUNT(*)".equals(args[0]) ? 1 : 0;
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("COUNT(*)", Kind.AUTO, 0, 0)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("2", result.getString("COUNT(*)"), "explicit count-shaped label string access");
    }

    @Test
    void resolvesOracleExpressionLabelsAgainstPostgresResultColumns() throws Exception {
        ResultSetMetaData metadata =
                JdbcProxy.proxy(
                        ResultSetMetaData.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getColumnCount" -> 2;
                                    case "getColumnLabel" ->
                                            ((Integer) args[0]) == 1 ? "btrim" : "max";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> {
                                        equal(1, args[0], "PostgreSQL trim expression ordinal");
                                        yield "A";
                                    }
                                    case "findColumn" -> "btrim".equals(args[0]) ? 1 : 2;
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(
                        List.of(
                                new Column(
                                        "TRIM(OC_H_CNVERS_AFTER_CODE1)", Kind.VARCHAR2, null, null),
                                new Column(
                                        "MAX(OC_H_CNVERS_AFTER_CODE1)",
                                        Kind.VARCHAR2,
                                        null,
                                        null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("A", result.getString("TRIM(OC_H_CNVERS_AFTER_CODE1)"), "Oracle trim label");
        equal(2, result.findColumn("MAX(OC_H_CNVERS_AFTER_CODE1)"), "Oracle max label");
    }

    @Test
    void resolvesExpressionLabelsByOrdinalWhenPostgresLabelsCollide() throws Exception {
        ResultSetMetaData metadata =
                JdbcProxy.proxy(
                        ResultSetMetaData.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getColumnCount" -> 2;
                                    case "getColumnLabel" -> "btrim";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" ->
                                            args[0] instanceof Integer ordinal
                                                    ? (ordinal == 1 ? "A" : "B")
                                                    : "A";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(
                        List.of(
                                new Column("TRIM(FIRST_VALUE)", Kind.VARCHAR2, null, null),
                                new Column("TRIM(SECOND_VALUE)", Kind.VARCHAR2, null, null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("B", result.getString("TRIM(SECOND_VALUE)"), "second expression result");
        equal(2, result.findColumn("TRIM(SECOND_VALUE)"), "second expression ordinal");
    }

    @Test
    void preservesOriginalLabelWhenDelegateMetadataIsUnavailable() throws Exception {
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("getMetaData")) {
                                throw new SQLException("metadata unavailable");
                            }
                            if (method.getName().equals("getString")) {
                                equal("TRIM(VALUE)", args[0], "original expression label");
                                return "A";
                            }
                            return defaultValue(method.getReturnType());
                        });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(
                        List.of(new Column("TRIM(VALUE)", Kind.VARCHAR2, null, null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("A", result.getString("TRIM(VALUE)"), "fallback string result");
    }

    @Test
    void preservesOriginalLabelWhenPlannedLabelsAreAmbiguous() throws Exception {
        ResultSetMetaData metadata = metadata(Types.VARCHAR, "text", 10, 0);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("getMetaData")) return metadata;
                            if (method.getName().equals("getString")) {
                                equal("VALUE", args[0], "ambiguous planned label");
                                return "A";
                            }
                            if (method.getName().equals("findColumn")) return 1;
                            return defaultValue(method.getReturnType());
                        });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(
                        List.of(
                                new Column("VALUE", Kind.VARCHAR2, null, null),
                                new Column("VALUE", Kind.VARCHAR2, null, null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("A", result.getString("VALUE"), "ambiguous label result");
    }

    @Test
    void preservesStringValueWhenMetadataDetailsAreUnavailable() throws Exception {
        ResultSetMetaData metadata =
                JdbcProxy.proxy(
                        ResultSetMetaData.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("getColumnType")) {
                                throw new SQLException("column type unavailable");
                            }
                            return defaultValue(method.getReturnType());
                        });
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> "A";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("VALUE", Kind.AUTO, null, null)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("A", result.getString(1), "raw string result");
    }

    @Test
    void padsFixedCharStringValuesToTheDeclaredWidth() throws Exception {
        ResultSetMetaData metadata = metadata(Types.CHAR, "bpchar", 3, 0, 3);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> "A";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultMetadataPlan plan =
                new ResultMetadataPlan(List.of(new Column("VALUE", Kind.CHAR, 3, 0)));
        ResultSet result = (ResultSet) JdbcMetadata.oracleResult(delegate, plan, null, null);

        equal("A  ", result.getString(1), "fixed CHAR string padding");
    }

    @Test
    void padsFixedCharToTheDeclaredOracleByteWidth() throws Exception {
        ResultSetMetaData metadata = metadata(Types.CHAR, "bpchar", 15, 0, 15);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> "テキスト１" + " ".repeat(10);
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet result =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                delegate,
                                new ResultMetadataPlan(
                                        List.of(new Column("VALUE", Kind.CHAR, 15, 0))),
                                null,
                                null);

        equal("テキスト１", result.getString(1), "multibyte fixed CHAR byte padding");
    }

    @Test
    void leavesUnboundedCharMetadataWithoutAllocatingAnUnboundedPaddingBuffer() throws Exception {
        ResultSetMetaData metadata = metadata(Types.CHAR, "bpchar", Integer.MAX_VALUE, 0);
        ResultSet delegate =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata;
                                    case "getString" -> "A";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet wrapped =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                delegate,
                                new ResultMetadataPlan(
                                        List.of(new Column("VALUE", Kind.CHAR, null, null))),
                                null,
                                null);

        equal(
                Integer.MAX_VALUE,
                wrapped.getMetaData().getColumnDisplaySize(1),
                "unbounded display size");
        equal("A", wrapped.getString(1), "unbounded CHAR value");
    }

    @Test
    void contract() throws Exception {
        equal(
                32,
                JdbcBinds.bindWidth(
                        PreparedStatement.class.getMethod("setNull", int.class, int.class),
                        new Object[] {1, Types.VARCHAR}),
                "VARCHAR NULL bind width");
        equal(
                40,
                JdbcBinds.bindWidth(
                        PreparedStatement.class.getMethod(
                                "setBigDecimal", int.class, BigDecimal.class),
                        new Object[] {1, BigDecimal.TEN}),
                "NUMBER bind width");
        ResultMetadataPlan concat =
                new ResultMetadataPlan(List.of(new Column("VALUE", Kind.VARCHAR2, 1, 0, 1)));
        int[] widths = {0, 32};
        ResultSet result = result(metadata(Types.VARCHAR, "text", Integer.MAX_VALUE, 0), null);
        ResultSet wrapped = (ResultSet) JdbcMetadata.oracleResult(result, concat, widths, null);
        equal(33, wrapped.getMetaData().getPrecision(1), "VARCHAR concat precision");
        widths[1] = 40;
        equal(41, wrapped.getMetaData().getPrecision(1), "NUMBER concat precision");
        ResultMetadataPlan literal =
                new ResultMetadataPlan(List.of(new Column("VALUE", Kind.CHAR, 3, 0)));
        ResultSet literalResult =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.VARCHAR, "text", Integer.MAX_VALUE, 0), null),
                                literal,
                                null,
                                null);
        equal(Types.CHAR, literalResult.getMetaData().getColumnType(1), "Oracle literal JDBC type");
        equal("CHAR", literalResult.getMetaData().getColumnTypeName(1), "Oracle literal type name");
        equal(3, literalResult.getMetaData().getPrecision(1), "Oracle literal precision");
        ResultMetadataPlan expression =
                new ResultMetadataPlan(List.of(new Column("ROOT_ID", Kind.AUTO, 0, 0)));
        ResultSet numeric =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.NUMERIC, "numeric", 4, 0), null),
                                expression,
                                null,
                                null);
        equal(0, numeric.getMetaData().getPrecision(1), "numeric expression precision");
        ResultSet numberSource =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, callArgs) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata(Types.NUMERIC, "numeric", 10, 2);
                                    case "getBigDecimal" -> new BigDecimal("10700.00");
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet numberResult =
                (ResultSet) JdbcMetadata.oracleResult(numberSource, expression, null, null);
        equal(
                new BigDecimal("10700"),
                numberResult.getBigDecimal(1),
                "Oracle NUMBER getBigDecimal strips insignificant zeros");
        ResultSet numberStringSource =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, callArgs) ->
                                switch (method.getName()) {
                                    case "getMetaData" -> metadata(Types.NUMERIC, "numeric", 10, 3);
                                    case "getString" -> "10700.000";
                                    default -> defaultValue(method.getReturnType());
                                });
        ResultSet numberStringResult =
                (ResultSet) JdbcMetadata.oracleResult(numberStringSource, expression, null, null);
        equal(
                "10700",
                numberStringResult.getString(1),
                "Oracle NUMBER getString strips insignificant zeros");
        ResultSet text =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.VARCHAR, "text", 30, 0), null),
                                expression,
                                null,
                                null);
        equal(30, text.getMetaData().getPrecision(1), "AUTO precision must not override text");
        Source source = new Source(null, "bs_hierarchy", "id");
        ResultMetadataPlan sourcePlan =
                new ResultMetadataPlan(
                        List.of(new Column("ID", Kind.AUTO, null, null, null, source, null)));
        ResultSet sourceResult =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.NUMERIC, "numeric", 4, 0), 0),
                                sourcePlan,
                                null,
                                null);
        equal(
                ResultSetMetaData.columnNoNulls,
                sourceResult.getMetaData().isNullable(1),
                "source NOT NULL must survive structural rewrite");
        Source textSource = new Source(null, "bs_hierarchy", "name");
        ResultMetadataPlan textSourcePlan =
                new ResultMetadataPlan(
                        List.of(
                                new Column(
                                        "ROOT_NAME",
                                        Kind.AUTO,
                                        null,
                                        null,
                                        null,
                                        textSource,
                                        null)));
        ResultSet textSourceResult =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.VARCHAR, "text", Integer.MAX_VALUE, 0), 30),
                                textSourcePlan,
                                null,
                                null);
        equal(
                30,
                textSourceResult.getMetaData().getPrecision(1),
                "unbounded text expression must recover source VARCHAR precision");

        ResultMetadataPlan outer =
                new ResultMetadataPlan(
                        List.of(new Column("EMPNO", Kind.AUTO, null, null, null, source, 1)));
        ResultSet outerResult =
                (ResultSet)
                        JdbcMetadata.oracleResult(
                                result(metadata(Types.NUMERIC, "numeric", 4, 0), 0),
                                outer,
                                null,
                                null);
        equal(
                ResultSetMetaData.columnNullable,
                outerResult.getMetaData().isNullable(1),
                "optional join side must be nullable");
        System.out.println("JdbcMetadataTest OK");
    }

    private static ResultSet result(ResultSetMetaData metadata, Integer sourceNullable) {
        Statement statement = sourceNullable == null ? null : sourceStatement(sourceNullable);
        return JdbcProxy.proxy(
                ResultSet.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getMetaData" -> metadata;
                            case "getStatement" -> statement;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static Statement sourceStatement(int nullable) {
        int[] cursor = {0};
        ResultSet columns =
                JdbcProxy.proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "next" -> cursor[0]++ == 0;
                                    case "getInt" -> nullable;
                                    default -> defaultValue(method.getReturnType());
                                });
        DatabaseMetaData database =
                JdbcProxy.proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) -> {
                            if (method.getName().equals("getColumns")) {
                                cursor[0] = 0;
                                return columns;
                            }
                            return defaultValue(method.getReturnType());
                        });
        Connection connection =
                JdbcProxy.proxy(
                        Connection.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getSchema" -> "public";
                                    case "getMetaData" -> database;
                                    default -> defaultValue(method.getReturnType());
                                });
        return JdbcProxy.proxy(
                Statement.class,
                (proxy, method, args) ->
                        method.getName().equals("getConnection")
                                ? connection
                                : defaultValue(method.getReturnType()));
    }

    private static ResultSetMetaData metadata(int type, String typeName, int precision, int scale) {
        return metadata(type, typeName, precision, scale, precision);
    }

    private static ResultSetMetaData metadata(
            int type, String typeName, int precision, int scale, int displaySize) {
        return JdbcProxy.proxy(
                ResultSetMetaData.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getColumnCount" -> 1;
                            case "getColumnType" -> type;
                            case "getColumnTypeName" -> typeName;
                            case "getPrecision" -> precision;
                            case "getScale" -> scale;
                            case "getColumnDisplaySize" -> displaySize;
                            case "getColumnLabel", "getColumnName" -> "value";
                            case "isNullable" -> ResultSetMetaData.columnNullable;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new AssertionError(type);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
