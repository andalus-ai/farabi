package tech.kayys.farabi.engine.port;

import tech.kayys.farabi.engine.domain.QueryRequest;

@FunctionalInterface
public interface QueryAuthorizer {
    /** Must reject unauthorized requests by throwing an exception. */
    void authorize(QueryRequest request);
}
