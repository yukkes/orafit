package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

/** Verifies timestamp binds survive Oracle multi-row INSERT translation. */
@Execution(ExecutionMode.SAME_THREAD)
final class TimestampUnionBindTest {
    @Test
    void timestampBindsInUnionSelectsAreTypedForPostgres() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement setup = connection.createStatement()) {
            setup.execute("DROP TABLE IF EXISTS orafit_junit_timestamp");
            setup.execute(
                    "CREATE TABLE orafit_junit_timestamp("
                            + "id integer, created_at timestamp without time zone)");
            try {
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO orafit_junit_timestamp(id, created_at) "
                                        + "(SELECT ?, ? FROM DUAL) "
                                        + "UNION ALL "
                                        + "(SELECT ?, ? FROM DUAL)")) {
                    Timestamp first = Timestamp.valueOf("2026-08-22 01:02:03.123456");
                    Timestamp second = Timestamp.valueOf("2026-08-22 04:05:06.654321");
                    insert.setInt(1, 1);
                    insert.setTimestamp(2, first);
                    insert.setInt(3, 2);
                    insert.setTimestamp(4, second);
                    assertEquals(2, insert.executeUpdate());
                }

                try (ResultSet rows =
                        setup.executeQuery(
                                "SELECT id, created_at FROM orafit_junit_timestamp ORDER BY id")) {
                    rows.next();
                    assertEquals(1, rows.getInt(1));
                    assertEquals(
                            Timestamp.valueOf("2026-08-22 01:02:03.123456"), rows.getTimestamp(2));
                    rows.next();
                    assertEquals(2, rows.getInt(1));
                    assertEquals(
                            Timestamp.valueOf("2026-08-22 04:05:06.654321"), rows.getTimestamp(2));
                }
            } finally {
                setup.execute("DROP TABLE orafit_junit_timestamp");
            }
        }
    }
}
