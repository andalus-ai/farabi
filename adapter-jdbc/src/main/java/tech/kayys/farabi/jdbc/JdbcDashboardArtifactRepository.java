package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DashboardArtifacts.*;

/**
 * PostgreSQL artifact store. Each published artifact is immutable; revisions
 * are unique per draft. Chart definitions use a narrow, versioned text encoding.
 */
public final class JdbcDashboardArtifactRepository implements Repository {
    private final DataSource source;
    public JdbcDashboardArtifactRepository(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public void save(Published artifact) {
        Objects.requireNonNull(artifact);
        String sql="INSERT INTO farabi_dashboard_artifacts(id,draft_id,workspace_id,title,revision,charts,prompt_provenance,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,artifact.id());p.setObject(2,artifact.draftId());p.setString(3,artifact.workspaceId());
            p.setString(4,artifact.title());p.setInt(5,artifact.revision());p.setString(6,encode(artifact.charts()));
            p.setString(7,artifact.promptProvenance());p.setString(8,artifact.publishedBy());
            p.setTimestamp(9,Timestamp.from(artifact.publishedAt()));p.executeUpdate();
        } catch(SQLException ex) { throw new IllegalStateException("publish dashboard failed",ex); }
    }
    @Override public Optional<Published> find(UUID id) {
        Objects.requireNonNull(id);
        String sql="SELECT id,draft_id,workspace_id,title,revision,charts,prompt_provenance,published_by,published_at FROM farabi_dashboard_artifacts WHERE id=?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next()) return Optional.empty();
                return Optional.of(new Published(r.getObject("id",UUID.class),r.getObject("draft_id",UUID.class),
                    r.getString("workspace_id"),r.getString("title"),r.getInt("revision"),decode(r.getString("charts")),
                    r.getString("prompt_provenance"),r.getString("published_by"),r.getTimestamp("published_at").toInstant()));
            }
        } catch(SQLException ex) { throw new IllegalStateException("read dashboard failed",ex); }
    }
    static String encode(List<Chart> charts) {
        // Length-prefixed fields avoid delimiter ambiguity and external JSON dependencies.
        StringBuilder result=new StringBuilder();
        result.append(charts.size()).append(':');
        for(Chart chart:charts) for(String field:List.of(chart.id(),chart.kind(),chart.datasetId(),chart.measureId()))
            result.append(field.length()).append(':').append(field);
        return result.toString();
    }
    static List<Chart> decode(String data) {
        if(data==null) throw new IllegalArgumentException("missing chart data");
        int[] cursor={0};
        int count=readInt(data,cursor);
        if(count<0||count>10000) throw new IllegalArgumentException("invalid chart count");
        List<Chart> charts=new ArrayList<>();
        for(int i=0;i<count;i++) charts.add(new Chart(readField(data,cursor),readField(data,cursor),readField(data,cursor),readField(data,cursor)));
        if(cursor[0]!=data.length()) throw new IllegalArgumentException("trailing chart data");
        return List.copyOf(charts);
    }
    private static String readField(String data,int[] cursor) {
        int length=readInt(data,cursor);
        if(length<0||length>data.length()-cursor[0]) throw new IllegalArgumentException("invalid field length");
        String value=data.substring(cursor[0],cursor[0]+length);cursor[0]+=length;return value;
    }
    private static int readInt(String data,int[] cursor) {
        int colon=data.indexOf(':',cursor[0]);
        if(colon<0||colon==cursor[0]) throw new IllegalArgumentException("invalid encoding");
        int value;
        try { value=Integer.parseInt(data.substring(cursor[0],colon)); }
        catch(NumberFormatException ex) { throw new IllegalArgumentException("invalid encoded number",ex); }
        cursor[0]=colon+1;return value;
    }
}
