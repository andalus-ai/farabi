package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/** Deterministic grid layout (12 columns) shared by the agent proposer and the conventional builder. */
public final class DashboardLayouts {
    private DashboardLayouts() {}

    /** KPIs flow three per row; charts two per row; tables take a full row. Never overlaps. */
    public static List<Placement> flow(List<ChartSpec> charts) {
        List<Placement> out = new ArrayList<>();
        int x = 0, y = 0, rowHeight = 0;
        boolean previousKpi = false;
        boolean first = true;
        for (ChartSpec c : charts) {
            boolean kpi = c.kind() == Kind.KPI;
            int w = kpi ? 4 : c.kind() == Kind.TABLE ? 12 : 6;
            int h = kpi ? 2 : 4;
            if (!first && (kpi != previousKpi || x + w > 12)) { y += rowHeight; x = 0; rowHeight = 0; }
            out.add(new Placement(c.id(), x, y, w, h));
            x += w; rowHeight = Math.max(rowHeight, h);
            previousKpi = kpi; first = false;
        }
        return out;
    }
}
