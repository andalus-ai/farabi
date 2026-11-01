package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, provider-neutral dataset metadata owned by the Farabi engine. */
public final class DatasetContracts {
    private DatasetContracts() {}

    public enum Format {
        CSV, TSV, XLSX, JSON, JSONL, PARQUET, DATABASE_TABLE, LAKEHOUSE_TABLE, UNKNOWN
    }

    public enum Lifecycle {
        REGISTERED, PROFILING, PROFILED, PROFILE_FAILED, DELETE_PENDING, ARCHIVED
    }

    public enum LogicalType {
        BOOLEAN, INTEGER, DECIMAL, STRING, DATE, TIME, TIMESTAMP, BINARY, JSON, UNKNOWN
    }

    public record Dataset(
            UUID id,
            String workspaceId,
            String name,
            String sourceId,
            Format format,
            String locationRef,
            Lifecycle lifecycle,
            Instant createdAt,
            Instant updatedAt) {
        public Dataset {
            Objects.requireNonNull(id, "id");
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(name, "name");
            QueryContracts.required(sourceId, "sourceId");
            Objects.requireNonNull(format, "format");
            QueryContracts.required(locationRef, "locationRef");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt precedes createdAt");
            }
        }
    }

    public record Column(
            String name,
            LogicalType logicalType,
            boolean nullable,
            long nullCount,
            long distinctCount) {
        public Column {
            QueryContracts.required(name, "name");
            Objects.requireNonNull(logicalType, "logicalType");
            if (nullCount < 0 || distinctCount < 0) {
                throw new IllegalArgumentException("column profile counts cannot be negative");
            }
        }
    }

    /** A profile is descriptive metadata; it must never be treated as an authorization grant. */
    public record Profile(
            UUID datasetId,
            String workspaceId,
            long rowCount,
            List<Column> columns,
            Instant profiledAt,
            String profilerVersion) {
        public Profile {
            Objects.requireNonNull(datasetId, "datasetId");
            QueryContracts.required(workspaceId, "workspaceId");
            if (rowCount < 0) throw new IllegalArgumentException("rowCount cannot be negative");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            Objects.requireNonNull(profiledAt, "profiledAt");
            QueryContracts.required(profilerVersion, "profilerVersion");
        }
    }
}
