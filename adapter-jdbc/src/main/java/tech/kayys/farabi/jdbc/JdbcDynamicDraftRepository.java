package tech.kayys.farabi.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DynamicDashboardService.DraftRepository;
import tech.kayys.farabi.engine.DynamicDashboardService.DynamicDraft;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/**
 * SQL persistence for dynamic dashboard drafts. Every statement is scoped by workspace, and promotion is a
 * single conditional UPDATE so two concurrent promotions of one draft cannot both win.
 */
public final class JdbcDynamicDraftRepository implements DraftRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private final DataSource source;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcDynamicDraftRepository(DataSource source, ObjectMapper json, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public void save(DynamicDraft d) {
        Objects.requireNonNull(d);
        String sql = "INSERT INTO farabi_dynamic_drafts(id,lineage_id,workspace_id,owner_id,prompt,spec_json,assumptions_json,proposer,parent_manifest_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, d.id()); p.setObject(2, d.lineageId()); p.setString(3, d.workspaceId()); p.setString(4, d.ownerId());
            p.setString(5, d.prompt()); p.setString(6, json.writeValueAsString(d.spec())); p.setString(7, json.writeValueAsString(d.assumptions()));
            p.setString(8, d.proposer()); p.setObject(9, d.parentManifestId());
            p.setTimestamp(10, Timestamp.from(d.createdAt())); p.setTimestamp(11, Timestamp.from(d.expiresAt()));
            p.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("save draft failed", e); }
    }

    @Override public Optional<DynamicDraft> find(UUID id, String workspaceId) {
        Objects.requireNonNull(id);
        String sql = "SELECT id,lineage_id,workspace_id,owner_id,prompt,spec_json,assumptions_json,proposer,parent_manifest_id,created_at,expires_at FROM farabi_dynamic_drafts WHERE id=? AND workspace_id=?";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, id); p.setString(2, workspaceId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new DynamicDraft(r.getObject("id", UUID.class), r.getObject("lineage_id", UUID.class),
                    r.getString("workspace_id"), r.getString("owner_id"), r.getString("prompt"),
                    json.readValue(r.getString("spec_json"), DashboardSpec.class), json.readValue(r.getString("assumptions_json"), STRINGS),
                    r.getString("proposer"), r.getObject("parent_manifest_id", UUID.class),
                    r.getTimestamp("created_at").toInstant(), r.getTimestamp("expires_at").toInstant()));
            }
        } catch (Exception e) { throw new IllegalStateException("read draft failed", e); }
    }

    @Override public boolean markPromoted(UUID id, String workspaceId) {
        String sql = "UPDATE farabi_dynamic_drafts SET promoted_at=? WHERE id=? AND workspace_id=? AND promoted_at IS NULL";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setTimestamp(1, Timestamp.from(clock.instant())); p.setObject(2, id); p.setString(3, workspaceId);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("mark draft promoted failed", e); }
    }

    @Override public void reopen(UUID id, String workspaceId) {
        String sql = "UPDATE farabi_dynamic_drafts SET promoted_at=NULL WHERE id=? AND workspace_id=?";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, id); p.setString(2, workspaceId);
            p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("reopen draft failed", e); }
    }
}
