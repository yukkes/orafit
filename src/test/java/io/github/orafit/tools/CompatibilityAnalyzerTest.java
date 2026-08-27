package io.github.orafit.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CompatibilityAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void classifiesAcceptedRejectedAndOutsideSqlWithoutASecondSplitter() throws Exception {
        Files.writeString(
                tempDir.resolve("accepted.sql"),
                "SELECT 1 FROM dual;\nSELECT UPPER('abc') FROM dual;\n");
        Files.writeString(
                tempDir.resolve("plsql.sql"),
                "BEGIN UPDATE account SET status = 'ACTIVE' WHERE account_id = ?; END;\n");
        Files.writeString(
                tempDir.resolve("rejected.sql"),
                "SELECT * FROM sales PIVOT (SUM(amount) FOR quarter IN ('Q1', 'Q2'));\n");
        Files.writeString(
                tempDir.resolve("outside.sql"), "CREATE TABLE migration_only(id NUMBER);\n");
        Files.writeString(tempDir.resolve("ignored.txt"), "SELECT 1 FROM dual;\n");

        CompatibilityAnalyzer.Report report = new CompatibilityAnalyzer().analyze(List.of(tempDir));

        assertEquals(4, report.files());
        assertEquals(5, report.statements());
        assertEquals(3, report.accepted());
        assertEquals(0, report.passthrough());
        assertEquals(3, report.rewritten());
        assertEquals(1, report.rejected());
        assertEquals(1, report.outside());
        assertEquals(1, report.rejectionCodes().get("UNSUPPORTED_PIVOT"));
    }
}
