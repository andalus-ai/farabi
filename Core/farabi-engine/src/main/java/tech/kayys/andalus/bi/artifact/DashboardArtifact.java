package tech.kayys.andalus.bi.artifact;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A saved, versioned dashboard. Create a new version for edits rather than mutating
 * a published artifact in place. Provenance records how the dashboard was authored.
 */
public record DashboardArtifact(
        UUID id,
        String workspaceId,
        String title,
        String description,
        int version,
        List<WidgetSpec> widgets,
        Provenance provenance,
        Instant createdAt) {

    public enum AuthoringMode { MANUAL, AGENT_PROMOTED, IMPORTED }

    public record Provenance(AuthoringMode mode, String actorId, String sourceRequestId, String semanticModelVersion) {
        public Provenance {
            Objects.requireNonNull(mode, "mode");
            actorId = required("actorId", actorId);
            sourceRequestId = sourceRequestId == null ? "" : sourceRequestId.trim();
            semanticModelVersion = semanticModelVersion == null ? "" : semanticModelVersion.trim();
        }
    }

    public DashboardArtifact {
        id = Objects.requireNonNull(id, "id");
        workspaceId = required("workspaceId", workspaceId);
        title = required("title", title);
        description = description == null ? "" : description.trim();
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        widgets = List.copyOf(Objects.requireNonNull(widgets, "widgets"));
        if (widgets.isEmpty()) throw new IllegalArgumentException("dashboard must contain at least one widget");
        var ids = new HashSet<String>();
        for (WidgetSpec widget : widgets) {
            Objects.requireNonNull(widget, "widget");
            if (!ids.add(widget.id())) throw new IllegalArgumentException("duplicate widget id: " + widget.id());
        }
        provenance = Objects.requireNonNull(provenance, "provenance");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static DashboardArtifact create(
            String workspaceId, String title, String description,
            List<WidgetSpec> widgets, Provenance provenance) {
        return new DashboardArtifact(UUID.randomUUID(), workspaceId, title, description,
                1, widgets, provenance, Instant.now());
    }

    /** Create the next immutable version while preserving the dashboard identity. */
    public DashboardArtifact nextVersion(String newTitle, String newDescription, List<WidgetSpec> newWidgets,
                                         Provenance newProvenance) {
        return new DashboardArtifact(id, workspaceId, newTitle, newDescription,
                version + 1, newWidgets, newProvenance, Instant.now());
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
