package tech.kayys.farabi.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class VisualizationComposerTest {
    private static ChartSpec chart(String id) {
        return new ChartSpec(id,Kind.BAR,"Sales",new Binding("sales","region","revenue",null),List.of());
    }
    @Test void composesDashboardWithLayout() {
        var spec=new DashboardSpec("Overview",List.of(chart("a"),chart("b")),
            List.of(new Placement("a",0,0,6,3),new Placement("b",6,0,6,3)));
        var composition=new VisualizationComposer().compose("ws","alice",spec,"prompt");
        assertEquals(2,composition.draft().charts().size());
        assertEquals("ws",composition.draft().workspaceId());
        assertEquals("prompt",composition.draft().promptProvenance());
    }
    @Test void rejectsOverlapsAndMissingPlacements() {
        assertThrows(IllegalArgumentException.class,()->new DashboardSpec("Overview",List.of(chart("a"),chart("b")),
            List.of(new Placement("a",0,0,7,3),new Placement("b",6,0,6,3))));
        assertThrows(IllegalArgumentException.class,()->new DashboardSpec("Overview",List.of(chart("a")),List.of()));
        assertThrows(IllegalArgumentException.class,()->new Placement("a",10,0,3,2));
    }
    @Test void defensiveCopies() {
        var charts=new ArrayList<>(List.of(chart("a")));
        var layout=new ArrayList<>(List.of(new Placement("a",0,0,12,3)));
        var spec=new DashboardSpec("Overview",charts,layout);
        charts.clear();layout.clear();
        assertEquals(1,spec.charts().size());
        assertEquals(1,spec.layout().size());
    }
}
