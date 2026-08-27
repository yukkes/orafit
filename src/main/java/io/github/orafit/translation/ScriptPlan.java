package io.github.orafit.translation;

import java.util.List;

/** Execution plan for an anonymous block lowered to multiple ordinary SQL statements. */
public record ScriptPlan(List<Statement> statements) {
    private static final ScriptPlan NONE = new ScriptPlan(List.of());

    public ScriptPlan {
        statements = List.copyOf(statements);
        if (!statements.isEmpty()) {
            for (Statement statement : statements) {
                if (statement == null)
                    throw new IllegalArgumentException("script statement must not be null");
            }
        }
    }

    public static ScriptPlan none() {
        return NONE;
    }

    public boolean present() {
        return !statements.isEmpty();
    }

    public record Statement(String sql, BindLineage binds) {
        public Statement {
            if (sql == null || sql.isBlank())
                throw new IllegalArgumentException("script SQL must not be blank");
            if (binds == null) throw new IllegalArgumentException("script binds must not be null");
        }
    }
}
