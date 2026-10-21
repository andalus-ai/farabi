package tech.kayys.farabi.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/** PostgreSQL persistence for complete, immutable dashboard visualization manifests. */
public final class JdbcDashboardManifestRepository implements Repository {
 private final DataSource source;
 private final ObjectMapper json;
 public JdbcDashboardManifestRepository(DataSource source,ObjectMapper json){
  this.source=Objects.requireNonNull(source);this.json=Objects.requireNonNull(json);
 }
 @Override public void save(Manifest m){
  Objects.requireNonNull(m);
  String sql="INSERT INTO farabi_dashboard_manifests(id,draft_id,workspace_id,revision,schema_version,spec_json,prompt_provenance,published_by,published_at) VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,?)";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setObject(1,m.id());p.setObject(2,m.draftId());p.setString(3,m.workspaceId());
   p.setInt(4,m.revision());p.setInt(5,m.schemaVersion());p.setString(6,json.writeValueAsString(m.spec()));
   p.setString(7,m.promptProvenance());p.setString(8,m.publishedBy());
   p.setTimestamp(9,Timestamp.from(m.publishedAt()));p.executeUpdate();
  }catch(Exception e){throw new IllegalStateException("save manifest failed",e);}
 }
 @Override public Optional<Manifest> find(UUID id,String workspace){
  Objects.requireNonNull(id);
  String sql="SELECT id,draft_id,workspace_id,revision,schema_version,spec_json,prompt_provenance,published_by,published_at FROM farabi_dashboard_manifests WHERE id=? AND workspace_id=?";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setObject(1,id);p.setString(2,workspace);
   try(ResultSet r=p.executeQuery()){
    if(!r.next())return Optional.empty();
    return Optional.of(new Manifest(r.getObject("id",UUID.class),r.getObject("draft_id",UUID.class),
     r.getString("workspace_id"),r.getInt("revision"),r.getInt("schema_version"),
     json.readValue(r.getString("spec_json"),DashboardSpec.class),r.getString("prompt_provenance"),
     r.getString("published_by"),r.getTimestamp("published_at").toInstant()));
   }
  }catch(Exception e){throw new IllegalStateException("read manifest failed",e);}
 }
}
