package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetContracts.*;
import tech.kayys.farabi.engine.DatasetPorts.Catalog;

/** PostgreSQL-backed, workspace-scoped dataset catalog. */
public final class JdbcDatasetCatalog implements Catalog {
    private final DataSource source;

    public JdbcDatasetCatalog(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public boolean create(Dataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        String sql = "INSERT INTO farabi_datasets(id,workspace_id,name,source_id,format,location_ref,lifecycle,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, dataset.id()); p.setString(2, dataset.workspaceId()); p.setString(3, dataset.name());
            p.setString(4, dataset.sourceId()); p.setString(5, dataset.format().name()); p.setString(6, dataset.locationRef());
            p.setString(7, dataset.lifecycle().name()); p.setTimestamp(8, Timestamp.from(dataset.createdAt()));
            p.setTimestamp(9, Timestamp.from(dataset.updatedAt())); p.executeUpdate();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) return false;
            throw new IllegalStateException("create dataset failed", e);
        }
    }

    @Override
    public Optional<Dataset> find(UUID id, String workspaceId) {
        Objects.requireNonNull(id, "id"); required(workspaceId, "workspaceId");
        String sql = "SELECT id,workspace_id,name,source_id,format,location_ref,lifecycle,created_at,updated_at FROM farabi_datasets WHERE id=? AND workspace_id=?";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, id); p.setString(2, workspaceId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Dataset(r.getObject("id", UUID.class), r.getString("workspace_id"),
                        r.getString("name"), r.getString("source_id"), Format.valueOf(r.getString("format")),
                        r.getString("location_ref"), Lifecycle.valueOf(r.getString("lifecycle")),
                        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("find dataset failed", e);
        }
    }

    @Override
    public Optional<Profile> findProfile(UUID datasetId, String workspaceId) {
        Objects.requireNonNull(datasetId, "datasetId"); required(workspaceId, "workspaceId");
        String profileSql = "SELECT row_count,profiled_at,profiler_version FROM farabi_dataset_profiles WHERE dataset_id=? AND workspace_id=?";
        String columnsSql = "SELECT name,logical_type,nullable,null_count,distinct_count FROM farabi_dataset_profile_columns WHERE dataset_id=? AND workspace_id=? ORDER BY ordinal";
        try (Connection c = source.getConnection()) {
            long rows; Timestamp profiledAt; String version;
            try (PreparedStatement p = c.prepareStatement(profileSql)) {
                p.setObject(1, datasetId); p.setString(2, workspaceId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    rows = r.getLong("row_count"); profiledAt = r.getTimestamp("profiled_at"); version = r.getString("profiler_version");
                }
            }
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(columnsSql)) {
                p.setObject(1, datasetId); p.setString(2, workspaceId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) columns.add(new Column(r.getString("name"), LogicalType.valueOf(r.getString("logical_type")),
                            r.getBoolean("nullable"), r.getLong("null_count"), r.getLong("distinct_count")));
                }
            }
            return Optional.of(new Profile(datasetId, workspaceId, rows, columns, profiledAt.toInstant(), version));
        } catch (SQLException e) {
            throw new IllegalStateException("find dataset profile failed", e);
        }
    }

    @Override
    public void saveProfile(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        String update = "UPDATE farabi_dataset_profiles SET row_count=?,profiled_at=?,profiler_version=? WHERE dataset_id=? AND workspace_id=?";
        String parent = "INSERT INTO farabi_dataset_profiles(dataset_id,workspace_id,row_count,profiled_at,profiler_version) VALUES(?,?,?,?,?)";
        String delete = "DELETE FROM farabi_dataset_profile_columns WHERE dataset_id=? AND workspace_id=?";
        String insert = "INSERT INTO farabi_dataset_profile_columns(dataset_id,workspace_id,ordinal,name,logical_type,nullable,null_count,distinct_count) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM farabi_datasets WHERE id=? AND workspace_id=?")) {
                    check.setObject(1, profile.datasetId()); check.setString(2, profile.workspaceId());
                    try (ResultSet r = check.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("dataset not found in workspace");
                    }
                }
                int updated;
                try (PreparedStatement p = c.prepareStatement(update)) {
                    p.setLong(1, profile.rowCount()); p.setTimestamp(2, Timestamp.from(profile.profiledAt()));
                    p.setString(3, profile.profilerVersion()); p.setObject(4, profile.datasetId());
                    p.setString(5, profile.workspaceId()); updated = p.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement p = c.prepareStatement(parent)) {
                        p.setObject(1, profile.datasetId()); p.setString(2, profile.workspaceId());
                        p.setLong(3, profile.rowCount()); p.setTimestamp(4, Timestamp.from(profile.profiledAt()));
                        p.setString(5, profile.profilerVersion()); p.executeUpdate();
                    }
                }
                try (PreparedStatement p = c.prepareStatement(delete)) {
                    p.setObject(1, profile.datasetId()); p.setString(2, profile.workspaceId()); p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement(insert)) {
                    int ordinal = 0;
                    for (Column column : profile.columns()) {
                        p.setObject(1, profile.datasetId()); p.setString(2, profile.workspaceId()); p.setInt(3, ordinal++);
                        p.setString(4, column.name()); p.setString(5, column.logicalType().name()); p.setBoolean(6, column.nullable());
                        p.setLong(7, column.nullCount()); p.setLong(8, column.distinctCount()); p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("save dataset profile failed", e);
            } finally { c.setAutoCommit(true); }
        } catch (SQLException e) {
            throw new IllegalStateException("save dataset profile failed", e);
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
