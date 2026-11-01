package tech.kayys.farabi.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Reports;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Finding;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.FindingType;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Report;

/** Persists provider inventory snapshots and their workspace-scoped findings. */
public final class JdbcDatasetStorageInventoryReportStore implements Reports {
    private final DataSource source;

    public JdbcDatasetStorageInventoryReportStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override public void save(Report report) {
        Objects.requireNonNull(report, "report");
        String header = "INSERT INTO farabi_storage_inventory_reports(id,workspace_id,inspected_at,orphan_grace_seconds,catalog_objects,provider_objects,finding_count) VALUES(?,?,?,?,?,?,?)";
        String detail = "INSERT INTO farabi_storage_inventory_findings(report_id,workspace_id,finding_index,finding_type,location_ref,expected_bytes,actual_bytes,object_last_modified) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement(header)) {
                    p.setObject(1, report.id());
                    p.setString(2, report.workspaceId());
                    p.setTimestamp(3, Timestamp.from(report.inspectedAt()));
                    p.setLong(4, report.orphanGracePeriod().toSeconds());
                    p.setInt(5, report.catalogObjects());
                    p.setInt(6, report.providerObjects());
                    p.setInt(7, report.findings().size());
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement(detail)) {
                    for (int i = 0; i < report.findings().size(); i++) {
                        Finding finding = report.findings().get(i);
                        p.setObject(1, report.id());
                        p.setString(2, report.workspaceId());
                        p.setInt(3, i);
                        p.setString(4, finding.type().name());
                        p.setString(5, finding.locationRef());
                        p.setLong(6, finding.expectedBytes());
                        p.setLong(7, finding.actualBytes());
                        if (finding.objectLastModified() == null) p.setTimestamp(8, null);
                        else p.setTimestamp(8, Timestamp.from(finding.objectLastModified()));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("persist storage inventory report failed", e);
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("persist storage inventory report failed", e);
        }
    }

    @Override public java.util.Optional<Report> find(java.util.UUID reportId, String workspaceId) {
        if (reportId == null || workspaceId == null || workspaceId.isBlank())
            throw new IllegalArgumentException("reportId and workspaceId required");
        String sql = "SELECT id,workspace_id,inspected_at,orphan_grace_seconds,catalog_objects,provider_objects FROM farabi_storage_inventory_reports WHERE id=? AND workspace_id=?";
        try (Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1, reportId); p.setString(2, workspaceId);
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) return java.util.Optional.empty();
                List<Finding> findings = new ArrayList<>();
                try (PreparedStatement d=c.prepareStatement("SELECT finding_type,location_ref,expected_bytes,actual_bytes,object_last_modified FROM farabi_storage_inventory_findings WHERE report_id=? AND workspace_id=? ORDER BY finding_index")) {
                    d.setObject(1, reportId); d.setString(2, workspaceId);
                    try (ResultSet rows=d.executeQuery()) {
                        while (rows.next()) {
                            Timestamp modified=rows.getTimestamp("object_last_modified");
                            findings.add(new Finding(FindingType.valueOf(rows.getString("finding_type")),
                                    rows.getString("location_ref"), rows.getLong("expected_bytes"),
                                    rows.getLong("actual_bytes"), modified==null?null:modified.toInstant()));
                        }
                    }
                }
                return java.util.Optional.of(new Report(reportId, workspaceId, r.getTimestamp("inspected_at").toInstant(),
                        Duration.ofSeconds(r.getLong("orphan_grace_seconds")), r.getInt("catalog_objects"),
                        r.getInt("provider_objects"), findings));
            }
        } catch (SQLException e) { throw new IllegalStateException("read storage inventory report failed", e); }
    }

    @Override public List<Report> recent(String workspaceId, int limit) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        String headers = "SELECT id,workspace_id,inspected_at,orphan_grace_seconds,catalog_objects,provider_objects FROM farabi_storage_inventory_reports WHERE workspace_id=? ORDER BY inspected_at DESC,id DESC LIMIT ?";
        String details = "SELECT finding_type,location_ref,expected_bytes,actual_bytes,object_last_modified FROM farabi_storage_inventory_findings WHERE report_id=? AND workspace_id=? ORDER BY finding_index";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(headers)) {
            p.setString(1, workspaceId);
            p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) {
                List<Report> reports = new ArrayList<>();
                while (r.next()) {
                    var id = r.getObject("id", java.util.UUID.class);
                    List<Finding> findings = new ArrayList<>();
                    try (PreparedStatement d = c.prepareStatement(details)) {
                        d.setObject(1, id);
                        d.setString(2, workspaceId);
                        try (ResultSet rows = d.executeQuery()) {
                            while (rows.next()) {
                                Timestamp modified = rows.getTimestamp("object_last_modified");
                                findings.add(new Finding(FindingType.valueOf(rows.getString("finding_type")),
                                        rows.getString("location_ref"), rows.getLong("expected_bytes"),
                                        rows.getLong("actual_bytes"), modified == null ? null : modified.toInstant()));
                            }
                        }
                    }
                    reports.add(new Report(id, workspaceId, r.getTimestamp("inspected_at").toInstant(),
                            Duration.ofSeconds(r.getLong("orphan_grace_seconds")), r.getInt("catalog_objects"),
                            r.getInt("provider_objects"), findings));
                }
                return List.copyOf(reports);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read storage inventory reports failed", e);
        }
    }
}
