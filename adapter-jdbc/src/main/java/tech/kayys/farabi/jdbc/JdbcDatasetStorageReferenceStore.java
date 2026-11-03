package tech.kayys.farabi.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.DatasetReference;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.DatasetReferences;

/** Workspace-scoped catalog view used by provider inventory reconciliation. */
public final class JdbcDatasetStorageReferenceStore implements DatasetReferences {
    private final DataSource source;
    public JdbcDatasetStorageReferenceStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public List<DatasetReference> listActive(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        String sql = "SELECT location_ref,storage_bytes FROM farabi_datasets WHERE workspace_id=? AND lifecycle<>'ARCHIVED'";
        try (Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,workspaceId);
            try (ResultSet r=p.executeQuery()) {
                List<DatasetReference> refs=new ArrayList<>();
                while(r.next()) refs.add(new DatasetReference(r.getString("location_ref"),r.getLong("storage_bytes")));
                return List.copyOf(refs);
            }
        } catch(SQLException e) { throw new IllegalStateException("list workspace dataset storage references failed",e); }
    }
}
