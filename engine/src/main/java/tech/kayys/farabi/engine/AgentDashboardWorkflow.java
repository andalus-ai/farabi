package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/**
 * Trusted boundary for agent-proposed dashboard specs.
 * AI output is data, never executable code or SQL.
 */
public final class AgentDashboardWorkflow {
 private final VisualizationComposer composer;
 private final ManifestPromotionService promotion;
 public AgentDashboardWorkflow(VisualizationComposer composer,ManifestPromotionService promotion){
  this.composer=Objects.requireNonNull(composer);this.promotion=Objects.requireNonNull(promotion);
 }
 public Manifest publish(String workspace,String subject,DashboardSpec proposal,String promptReference,int revision){
  QueryContracts.required(workspace,"workspace");QueryContracts.required(subject,"subject");
  QueryContracts.required(promptReference,"promptReference");
  Objects.requireNonNull(proposal);
  var composition=composer.compose(workspace,subject,proposal,promptReference);
  return promotion.publish(composition,workspace,subject,revision);
 }
}
