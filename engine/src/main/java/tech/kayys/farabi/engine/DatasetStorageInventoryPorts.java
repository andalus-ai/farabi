package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Provider-neutral, workspace-scoped object inventory; implementations must never cross workspace boundaries. */
public final class DatasetStorageInventoryPorts {
    private DatasetStorageInventoryPorts() {}

    public record ObjectEntry(String locationRef, long sizeBytes, Instant lastModified) {
        public ObjectEntry {
            if (locationRef == null || locationRef.isBlank() || sizeBytes < 0 || lastModified == null)
                throw new IllegalArgumentException("invalid inventory entry");
        }
    }

    public interface Inventory {
        /** Returns empty only when the referenced object is genuinely absent. */
        Optional<ObjectEntry> inspect(String locationRef, String workspaceId);
        /** Lists objects owned by this workspace only; callers apply an orphan grace period. */
        List<ObjectEntry> list(String workspaceId);
    }
}
