package tech.kayys.andalus.bi.query.jobs;

import java.time.Duration;

/** Deterministic bounded exponential backoff for worker retry scheduling. */
public final class QueryWorkerBackoff {
    private final Duration base;
    private final Duration maximum;

    public QueryWorkerBackoff(Duration base, Duration maximum) {
        if (base == null || maximum == null || base.isZero() || base.isNegative()
                || maximum.compareTo(base) < 0) {
            throw new IllegalArgumentException("invalid backoff bounds");
        }
        this.base = base;
        this.maximum = maximum;
    }

    public Duration delay(int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("attempt must be >= 0");
        Duration value = base;
        for (int i = 0; i < Math.min(attempt, 63); i++) {
            if (value.compareTo(maximum.dividedBy(2)) > 0) return maximum;
            value = value.multipliedBy(2);
            if (value.compareTo(maximum) >= 0) return maximum;
        }
        return value;
    }
}
