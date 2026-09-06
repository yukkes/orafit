package io.github.orafit.jdbc;

import io.github.orafit.translation.TranslationException;

import java.sql.SQLException;

/**
 * Central Oracle-facing SQLException adaptation for translation and delegated PostgreSQL errors.
 */
final class JdbcErrors {
    private JdbcErrors() {}

    static SQLException fromTranslation(TranslationException failure) {
        return switch (failure.code()) {
            case "COALESCE_ARITY" ->
                    new SQLException(
                            "ORA-00938: not enough arguments for function", "42000", 938, failure);
            case "NULLIF_FIRST_NULL" ->
                    new SQLException("ORA-00932: inconsistent datatypes", "42000", 932, failure);
            case "MERGE_ON_COLUMN_UPDATE" ->
                    new SQLException(
                            "ORA-38104: MERGE cannot update a target column referenced by ON",
                            "99999",
                            38104,
                            failure);
            case "CASE_DATATYPE" ->
                    new SQLException(
                            "ORA-00932: inconsistent datatypes: expected NUMBER got CHAR",
                            "42000",
                            932,
                            failure);
            case "IN_LIST_LIMIT" ->
                    new SQLException(
                            "ORA-01795: maximum number of expressions in a list is 1000",
                            "42000",
                            1795,
                            failure);
            case "PARSER" -> new SQLException(failure.getMessage(), "42000", failure);
            default -> new SQLException(failure.getMessage(), "0A000", failure);
        };
    }

    static SQLException fromDelegate(SQLException sql) {
        if (sql.getErrorCode() != 0) return sql;
        String message = sql.getMessage();
        if ("23514".equals(sql.getSQLState())
                && message != null
                && message.contains("orafit_byte_length_")) {
            return new SQLException("ORA-12899: value too large for column", "72000", 12899, sql);
        }
        if ("21000".equals(sql.getSQLState())
                && message != null
                && message.contains(
                        "more than one row returned by a subquery used as an expression")) {
            return new SQLException(
                    "ORA-01427: single-row subquery returns more than one row", "21000", 1427, sql);
        }
        if (message == null) return sql;
        int marker = message.indexOf("ORA-");
        if (marker < 0 || marker + 9 > message.length()) return sql;
        int code = 0;
        for (int i = marker + 4; i < marker + 9; i++) {
            char character = message.charAt(i);
            if (character < '0' || character > '9') return sql;
            code = code * 10 + (character - '0');
        }
        return new SQLException(message, sql.getSQLState(), code, sql);
    }
}
