package tech.kayys.farabi.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Stable content digest of a dashboard specification. A reviewer approves a digest, and promotion
 * only succeeds if the draft still has exactly that digest ("promote what you reviewed").
 */
public final class SpecDigest {
    private SpecDigest() {}

    public static String of(DashboardSpec spec) {
        StringBuilder b = new StringBuilder("spec-v1;");
        field(b, spec.title());
        for (ChartSpec c : spec.charts()) {
            field(b, c.id()); field(b, c.kind().name()); field(b, c.title());
            Binding bind = c.binding();
            field(b, bind.datasetId()); field(b, bind.categoryField()); field(b, bind.valueField()); field(b, bind.seriesField());
            for (Filter f : c.filters()) { field(b, f.field()); field(b, f.operator()); field(b, f.value()); }
            b.append('|');
        }
        for (Placement p : spec.layout()) field(b, p.chartId() + "@" + p.x() + "," + p.y() + "," + p.width() + "," + p.height());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(StringBuilder b, String v) {
        if (v == null) b.append("~;"); else b.append(v.length()).append(':').append(v).append(';');
    }
}
