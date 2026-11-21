package tech.kayys.farabi.engine;

import tech.kayys.farabi.engine.SemanticModel.Grain;

/** SQL syntax capabilities required by Farabi's structured semantic compiler. */
public interface SqlDialect {
    String id();

    String quoteIdentifier(String identifier);

    String renderDateBucket(Grain grain, String quotedColumn);

    String renderLimit(int limit);
}
