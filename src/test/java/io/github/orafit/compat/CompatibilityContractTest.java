package io.github.orafit.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

final class CompatibilityContractTest {
    private final OrafitEngine engine = new OrafitEngine();

    static Stream<CompatibilityCase> cases() {
        return CompatibilityCases.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void translationContractIsExplicit(CompatibilityCase test) throws Exception {
        if (test.mode() == CompatibilityCase.Mode.REJECT
                && test.rejectStage() == CompatibilityCase.RejectStage.TRANSLATION) {
            TranslationException exception =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            TranslationException.class, () -> engine.translate(test.sql()));
            assertEquals(test.orafitCode(), exception.code(), test.id());
            return;
        }

        try {
            Translation translation = engine.translate(test.sql());
            if ("core.metadata-basic".equals(test.id())) {
                assertFalse(
                        translation.sql().toUpperCase(Locale.ROOT).contains(" AS NUMBER"),
                        "Oracle type syntax must not leak into PostgreSQL SQL");
            }
        } catch (TranslationException exception) {
            if (test.mode() == CompatibilityCase.Mode.SAME
                    && test.oracleOutcome() == CompatibilityCase.Outcome.ERROR
                    && !test.orafitCode().isBlank()) {
                assertEquals(test.orafitCode(), exception.code(), test.id());
                return;
            }
            fail(
                    test.id()
                            + " unexpectedly rejected: "
                            + exception.code()
                            + " "
                            + exception.getMessage());
        }
    }
}
