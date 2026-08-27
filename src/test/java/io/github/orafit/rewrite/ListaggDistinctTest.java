package io.github.orafit.rewrite;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/**
 * Keeps post-18c LISTAGG DISTINCT fail-closed without adding it to the Oracle 18c reference suite.
 */
public final class ListaggDistinctTest {
    @Test
    void contract() throws Exception {
        OrafitEngine engine = new OrafitEngine();
        try {
            engine.translate(
                    "SELECT LISTAGG(DISTINCT job, ',') WITHIN GROUP (ORDER BY job) FROM emp");
            throw new AssertionError("LISTAGG DISTINCT must fail closed");
        } catch (TranslationException ex) {
            if (!"LISTAGG_DISTINCT_ORDER".equals(ex.code())) {
                throw new AssertionError("unexpected reject code: " + ex.code());
            }
        }
        System.out.println("ListaggDistinctTest OK");
    }
}
