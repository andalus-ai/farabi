package tech.kayys.farabi.engine;

import java.util.Objects;
import tech.kayys.farabi.engine.SemanticModel.Grain;

/** ANSI-style dialect matching Farabi's existing double-quote, DATE_TRUNC and LIMIT SQL. */
public final class AnsiSqlDialect implements SqlDialect {
    @Override public String id() { return "ansi"; }

    @Override public String quoteIdentifier(String identifier) {
        SemanticModel.identifier(identifier, "identifier");
        return "\"" + identifier + "\"";
    }

    @Override public String renderDateBucket(Grain grain, String quotedColumn) {
        Objects.requireNonNull(grain, "grain");
        QueryContracts.required(quotedColumn, "quotedColumn");
        return "DATE_TRUNC('" + grain.name() + "', " + quotedColumn + ")";
    }

    @Override public String renderLimit(int limit) {
        if (limit < 1 || limit > StructuredQueryCompiler.MAX_LIMIT)
            throw new IllegalArgumentException("limit must be within compiler bounds");
        return "LIMIT " + limit;
    }
}
