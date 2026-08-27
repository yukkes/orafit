package io.github.orafit;

import io.github.orafit.metadata.ResultMetadataPlanner;
import io.github.orafit.parse.BindCodec;
import io.github.orafit.parse.CallEnvelopeCodec;
import io.github.orafit.parse.ParserAdapter;
import io.github.orafit.parse.ReturningIntoCodec;
import io.github.orafit.parse.SqlGate;
import io.github.orafit.rewrite.DatabaseTypeCoercionRule;
import io.github.orafit.rewrite.RewriteEngine;
import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.CallPlan;
import io.github.orafit.translation.ColumnTypeResolver;
import io.github.orafit.translation.Feature;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ReturningPlan;
import io.github.orafit.translation.ScriptPlan;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.statement.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Translates one Oracle-oriented SQL statement or a bounded anonymous DML block into the PostgreSQL
 * contract owned by Orafit.
 *
 * <p>The lightweight gate keeps PostgreSQL-compatible statements off the structural parser.
 * Statements that need Oracle-specific handling are parsed once, lowered on one AST, and rendered
 * once. Unsupported or ambiguous Oracle semantics fail closed with {@link TranslationException}.
 */
public final class OrafitEngine {
    private final SqlGate gate = new SqlGate();
    private final BindCodec binds = new BindCodec();
    private final ReturningIntoCodec returning = new ReturningIntoCodec();
    private final CallEnvelopeCodec calls = new CallEnvelopeCodec();
    private final ParserAdapter parser = new ParserAdapter();
    private final ResultMetadataPlanner metadata = new ResultMetadataPlanner();
    private final RewriteEngine rewriter = new RewriteEngine();
    private final DatabaseTypeCoercionRule databaseTypes = new DatabaseTypeCoercionRule();

    /**
     * Translates SQL and records any bind, routine, RETURNING, script, or result-metadata
     * adjustments required by the JDBC wrapper.
     *
     * @param sql Oracle-oriented SQL presented by the application
     * @return immutable translation result for execution through pgJDBC
     * @throws TranslationException if Oracle semantics cannot be preserved within the supported
     *     contract
     * @throws IllegalArgumentException if {@code sql} is {@code null}
     */
    public Translation translate(String sql) throws TranslationException {
        return translate(sql, ColumnTypeResolver.NONE);
    }

    /** Translates with physical PostgreSQL column types supplied by the JDBC execution boundary. */
    public Translation translate(String sql, ColumnTypeResolver columnTypes)
            throws TranslationException {
        if (sql == null) throw new IllegalArgumentException("sql must not be null");
        SqlGate.Scan scan = gate.scan(sql);
        String input = scan.sql();
        boolean databaseCoercion =
                columnTypes != ColumnTypeResolver.NONE && scan.requiresDatabaseCoercion();
        if (!scan.requiresParser() && !scan.requiresStructuralParser() && !databaseCoercion) {
            return unchanged(input, scan, ResultMetadataPlan.none());
        }

        rewriter.validate(scan.features());
        BindCodec.Encoded encoded = binds.encode(input, scan);
        ReturningIntoCodec.Result returnResult =
                scan.features().contains(Feature.RETURNING_INTO)
                        ? returning.normalize(encoded.sql(), encoded.bindCount())
                        : new ReturningIntoCodec.Result(encoded.sql(), ReturningPlan.none());
        CallEnvelopeCodec.Result callResult =
                scan.features().contains(Feature.PLSQL)
                        ? calls.normalize(returnResult.sql(), encoded.bindCount())
                        : new CallEnvelopeCodec.Result(returnResult.sql(), CallPlan.none());
        List<Statement> statements =
                scan.script()
                        ? parser.parseStatements(callResult.sql())
                        : List.of(parser.parse(callResult.sql()));
        boolean script = scan.script();
        ResultMetadataPlan metadataPlan =
                script ? ResultMetadataPlan.none() : metadata.plan(statements.get(0), input);
        List<String> renderedStatements = new ArrayList<>(statements.size());
        boolean rewriteChanged = false;
        boolean databaseChanged = false;
        for (Statement statement : statements) {
            RewriteEngine.Result result = rewriter.rewrite(statement);
            rewriteChanged |= result.changed();
            databaseChanged |=
                    !scan.features().contains(Feature.CONNECT_BY)
                            && databaseTypes.rewrite(result.statement(), columnTypes);
            renderedStatements.add(
                    result.renderedSql() != null
                            ? result.renderedSql()
                            : result.statement().toString());
        }

        if (!script
                && !rewriteChanged
                && !databaseChanged
                && !returnResult.plan().present()
                && !callResult.plan().present()) {
            return unchanged(input, scan, metadataPlan);
        }

        String rendered = String.join(";", renderedStatements);
        if (!callResult.plan().present()
                && input.stripTrailing().endsWith(";")
                && !rendered.stripTrailing().endsWith(";")) {
            rendered += ";";
        }
        Set<Integer> omitted =
                returnResult.plan().present()
                        ? returnResult.plan().outputIndices()
                        : callResult.plan().outputIndices();
        if (script) {
            List<ScriptPlan.Statement> planStatements = new ArrayList<>(renderedStatements.size());
            for (String statement : renderedStatements) {
                BindCodec.Decoded decoded =
                        binds.decodeFragment(statement, encoded.bindCount(), omitted);
                planStatements.add(new ScriptPlan.Statement(decoded.sql(), decoded.lineage()));
            }
            BindCodec.Decoded combined = binds.decode(rendered, encoded.bindCount(), omitted);
            return new Translation(
                    combined.sql(),
                    BindLineage.identity(encoded.bindCount()),
                    returnResult.plan(),
                    callResult.plan(),
                    metadataPlan,
                    new ScriptPlan(planStatements),
                    scan.features(),
                    true);
        }
        BindCodec.Decoded decoded = binds.decode(rendered, encoded.bindCount(), omitted);
        return new Translation(
                decoded.sql(),
                decoded.lineage(),
                returnResult.plan(),
                callResult.plan(),
                metadataPlan,
                ScriptPlan.none(),
                scan.features(),
                true);
    }

    private static Translation unchanged(
            String sql, SqlGate.Scan scan, ResultMetadataPlan metadata) {
        return new Translation(
                sql,
                BindLineage.identity(scan.bindCount()),
                ReturningPlan.none(),
                CallPlan.none(),
                metadata,
                ScriptPlan.none(),
                scan.features(),
                scan.changed());
    }
}
