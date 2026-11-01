package tech.kayys.farabi.engine;

import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetContracts.Profile;

/** Replaceable edges for importing bytes and profiling their contents. */
public final class DatasetImportPorts {
    private DatasetImportPorts() {}

    /** Accepts an already-authorized upload or external source and returns a durable reference. */
    @FunctionalInterface
    public interface SourceIntake {
        StagedSource stage(ImportRequest request);
    }

    /** Computes a deterministic profile from the staged source; implementations must enforce resource limits. */
    @FunctionalInterface
    public interface Profiler {
        Profile profile(UUID datasetId, String workspaceId, StagedSource source);
    }

    public record ImportRequest(
            String workspaceId,
            String subjectId,
            String name,
            String sourceId,
            Format format,
            String originalFileName,
            long contentLength,
            String contentType) {
        public ImportRequest {
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(subjectId, "subjectId");
            QueryContracts.required(name, "name");
            QueryContracts.required(sourceId, "sourceId");
            if (format == null) throw new IllegalArgumentException("format required");
            QueryContracts.required(originalFileName, "originalFileName");
            if (contentLength < 0) throw new IllegalArgumentException("contentLength cannot be negative");
            QueryContracts.required(contentType, "contentType");
        }
    }

    public record StagedSource(Format format, String locationRef) {
        public StagedSource {
            if (format == null) throw new IllegalArgumentException("format required");
            QueryContracts.required(locationRef, "locationRef");
        }
    }
}
