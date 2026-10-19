package tech.kayys.farabi.engine.domain;

public record QuerySubmission(String providerJobId) {
    public QuerySubmission {
        if (providerJobId == null || providerJobId.isBlank()) throw new IllegalArgumentException("providerJobId required");
    }
}
