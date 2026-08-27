package io.github.orafit.jdbc;

import java.math.BigDecimal;

/** Shared Oracle NUMBER value normalization at JDBC boundaries. */
final class OracleNumber {
    private OracleNumber() {}

    static BigDecimal normalize(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
