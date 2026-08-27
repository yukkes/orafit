package io.github.orafit.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.ResultMetadataPlan.Column;
import io.github.orafit.translation.ResultMetadataPlan.Kind;

import org.junit.jupiter.api.Test;

/** Regression coverage for Oracle current-time pseudo-column metadata boundaries. */
final class CurrentTimeMetadataTest {
    @Test
    void qualifiedSystimestampRemainsAnOrdinaryColumn() throws Exception {
        Translation translation =
                new OrafitEngine().translate("SELECT e.SYSTIMESTAMP AS value FROM bs_emp e");
        Column column = translation.metadata().column(1);

        assertEquals(Kind.AUTO, column.kind());
        assertNull(column.precision());
        assertNull(column.scale());
        assertFalse(translation.sql().contains("orafit.systimestamp"));
    }
}
