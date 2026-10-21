package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/** UI adapter SPI: Tenun, Batik, or another frontend may implement this. */
public interface VisualizationRenderer<T> {
    T render(DashboardSpec spec,Map<String,List<Map<String,Object>>> datasets);
}
