package io.github.orafit.parse;

import io.github.orafit.translation.TranslationException;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Provider;
import net.sf.jsqlparser.parser.StringProvider;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.parser.TokenMgrError;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only class that directly owns the JSqlParser entry point. */
public final class ParserAdapter {
    private static final ExecutorService PARSER_EXECUTOR =
            Executors.newCachedThreadPool(
                    task -> {
                        Thread thread = new Thread(task);
                        thread.setDaemon(true);
                        return thread;
                    });

    public Statement parse(String sql) throws TranslationException {
        try {
            return CCJSqlParserUtil.parseStatement(
                    new StructuralParser(new StringProvider(SqlGate.parserSql(sql)))
                            .withAllowComplexParsing(false),
                    PARSER_EXECUTOR);
        } catch (JSQLParserException ex) {
            throw new TranslationException(
                    "PARSER", "JSqlParser could not parse structural SQL: " + ex.getMessage(), ex);
        }
    }

    /**
     * Preserves JSqlParser's nested-set lookahead without its 500-token cutoff. A legal Oracle IN
     * list can contain 1,000 expressions, so the cutoff otherwise misclassifies a parenthesized
     * SELECT as a parenthesized FROM item before reaching the following set operator.
     */
    private static final class StructuralParser extends CCJSqlParser {
        private StructuralParser(Provider provider) {
            super(provider);
        }

        @Override
        protected boolean isNestedSetOperationAhead() {
            try {
                if (getToken(1).kind != OPENING_BRACKET || getToken(2).kind != OPENING_BRACKET)
                    return false;

                int at = 3;
                while (getToken(at).kind == OPENING_BRACKET) at++;
                int first = getToken(at).kind;
                if (first != K_SELECT && first != K_WITH && first != K_VALUES) return false;

                int depth = at - 2;
                for (at++; ; at++) {
                    Token token = getToken(at);
                    if (token == null || token.kind == EOF) return false;
                    if (token.kind == OPENING_BRACKET) depth++;
                    else if (token.kind == CLOSING_BRACKET && --depth == 0) break;
                }

                int following = getToken(at + 1).kind;
                return following == K_UNION
                        || following == K_INTERSECT
                        || following == K_EXCEPT
                        || following == K_MINUS;
            } catch (TokenMgrError ex) {
                return false;
            }
        }
    }

    /**
     * Parses a SQL script into individual statements using the pinned parser's native entry point.
     * Runtime translation uses this only after the lexical gate has proved a bounded anonymous DML
     * block; compatibility tooling uses it for offline script analysis. Keeping both consumers here
     * prevents a second SQL splitter from becoming another source of truth.
     */
    public List<Statement> parseStatements(String sql) throws TranslationException {
        try {
            return List.copyOf(
                    CCJSqlParserUtil.parseStatements(
                            new StructuralParser(new StringProvider(SqlGate.parserSql(sql)))
                                    .withAllowComplexParsing(false),
                            PARSER_EXECUTOR));
        } catch (JSQLParserException ex) {
            throw new TranslationException(
                    "PARSER", "JSqlParser could not parse SQL script: " + ex.getMessage(), ex);
        }
    }

    public static List<Column> columns(Expression expression) {
        if (expression == null) return List.of();
        List<Column> result = new ArrayList<>();
        expression.accept(
                new ExpressionVisitorAdapter<Void>() {
                    @Override
                    public <S> Void visit(Column column, S context) {
                        result.add(column);
                        return super.visit(column, context);
                    }
                },
                null);
        return List.copyOf(result);
    }

    public static <T> List<T> nodes(Statement statement, Class<T> type) {
        if (statement instanceof ASTNodeAccess access) return nodes(access, type);
        if (statement instanceof CreateView createView && createView.getSelect() != null)
            return nodes((ASTNodeAccess) createView.getSelect(), type);
        if (statement instanceof Insert insert
                && insert.getSelect() != null
                && !(insert.getSelect() instanceof Values))
            return nodes((ASTNodeAccess) insert.getSelect(), type);
        return List.of();
    }

    public static <T> List<T> nodes(ASTNodeAccess access, Class<T> type) {
        if (access == null || access.getASTNode() == null) return List.of();
        List<T> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (type.isInstance(access)) {
            result.add(type.cast(access));
            seen.add(access);
        }
        collect(access.getASTNode(), type, seen, result);
        return List.copyOf(result);
    }

    private static <T> void collect(Node node, Class<T> type, Set<Object> seen, List<T> result) {
        if (node == null) return;
        Object value = node.jjtGetValue();
        if (value != null && seen.add(value) && type.isInstance(value))
            result.add(type.cast(value));
        Node[] children = node.jjtGetChildren();
        if (children != null) {
            for (Node child : children) collect(child, type, seen, result);
        }
    }
}
