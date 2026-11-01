# Statistical Intelligence and Research Analytics

**Status:** Product and architecture proposal. Algorithm families below are a capability catalogue and target design, not a claim that every method is implemented or validated in Farabi today.

## 1. Product vision

Farabi should support an end-to-end research workflow: a user imports data, describes a research or business question in natural language, chooses or delegates statistical methods, reviews assumptions, runs reproducible analyses, and receives interpretable results with publication-quality tables and visualizations.

The Statistical Intelligence Agent is a specialist agent capability orchestrated through the Andalus Framework/Runtime. It is not a free-form code execution agent. It proposes a research plan and method; Farabi's deterministic analysis engine validates data, assumptions, permissions, algorithm configuration and resource limits before execution.

Core promise: **bring data → ask a research question → select/validate methods → run reproducibly → explain results → visualize and export.**

## 2. User journey

1. **Import:** upload CSV, TSV, XLSX, JSON/JSONL, Parquet or supported database/lakehouse sources. Preserve the original file as an immutable source object.
2. **Profile:** infer schema and types; inspect missingness, duplicates, cardinality, ranges, distributions, outliers and potential identifiers. Show inferred types for user confirmation.
3. **Store:** create a tenant/workspace-scoped dataset asset with source lineage, schema version, retention policy and access controls. Do not silently overwrite source data.
4. **Describe purpose:** ask in natural language, e.g. “compare treatment and control”, “forecast monthly demand”, “test whether these variables are associated”, “cluster customers”, or “estimate the effect of this intervention”.
5. **Clarify design:** ask only essential questions such as outcome, predictor, group, unit of analysis, time column, study design, hypothesis, confidence level and practical constraints.
6. **Recommend:** agent proposes one or more suitable methods, explains why, lists assumptions and alternatives, and identifies missing design information.
7. **Validate:** deterministic validators check data types, sample size constraints where method-specific, assumptions/diagnostics, leakage, independence/time ordering, multiplicity and missing-data strategy.
8. **Execute:** run a versioned algorithm implementation in a resource-limited worker/sandbox with fixed configuration and recorded environment.
9. **Interpret:** report estimates, uncertainty, effect sizes, diagnostics, limitations and plain-language interpretation. Do not equate statistical significance with practical importance or causality.
10. **Present:** produce tables, charts, diagnostic plots, model summaries and reproducible reports. Save the analysis as an immutable artifact linked to dataset and code/configuration versions.

## 3. Statistical capability catalogue

Use a registry of algorithms with metadata, not a giant hard-coded agent prompt. Each method declares its purpose, supported data types/designs, assumptions, required parameters, diagnostics, output schema, implementation/library, version and known limitations.

### A. Data preparation and exploratory analysis
- Data dictionary, type inference and schema validation.
- Descriptive statistics: mean, median, mode, quantiles, variance, standard deviation, IQR, robust summaries.
- Frequency tables, cross-tabulation, missingness patterns and duplicate detection.
- Distribution analysis, outlier flags, transformations and scaling.
- Sampling, stratification, weighting and train/test split.
- Missing data: complete-case analysis, simple imputation and multiple imputation where supported; always record method and sensitivity.
- Feature engineering and reproducible preprocessing pipelines.

### B. Statistical inference and hypothesis testing
- Confidence intervals, bootstrap and permutation tests.
- Parametric and non-parametric comparisons: t-tests, paired tests, ANOVA/ANCOVA, Mann–Whitney, Wilcoxon, Kruskal–Wallis.
- Proportion/rate tests, chi-square and Fisher exact tests.
- Correlation: Pearson, Spearman, Kendall; partial correlation where justified.
- Multiple testing correction: Holm, Bonferroni, Benjamini–Hochberg/FDR.
- Effect sizes and power/sample-size planning.
- Bayesian estimation and hypothesis workflows as an explicitly identified optional method family.

### C. Regression and generalized models
- Linear, logistic, ordinal and multinomial regression.
- Poisson, negative-binomial and other generalized linear models.
- Robust, regularized and mixed-effects models where supported.
- Diagnostics: residuals, heteroscedasticity, collinearity, influence, calibration, separation and goodness of fit.
- Report coefficient uncertainty, effect interpretation and model limitations.

### D. Time series and forecasting
- Trend, seasonality, decomposition, stationarity and autocorrelation diagnostics.
- Naive/seasonal baselines, exponential smoothing, ARIMA/SARIMA and regression with time features.
- Backtesting with time-ordered splits, forecast intervals and error metrics (MAE, RMSE, MAPE/sMAPE with caveats).
- Change-point and anomaly detection.
- Prevent future leakage; compare against simple baselines before recommending complex models.

### E. Multivariate analysis and dimensionality reduction
- PCA, factor analysis, correspondence analysis and discriminant analysis.
- Clustering: k-means, hierarchical, DBSCAN and Gaussian mixtures where appropriate.
- Cluster validation, stability and sensitivity analysis.
- Canonical correlation and other multivariate methods as optional extensions.

### F. Machine learning and predictive analytics
- Classification, regression, ranking and calibrated probabilities.
- Tree ensembles, random forests, gradient boosting and interpretable baselines.
- Cross-validation, nested tuning where justified, feature importance and explainability.
- Model evaluation by appropriate metrics, subgroup performance and drift.
- Separate predictive association from causal inference.

### G. Causal and experimental research
- Randomized experiment analysis, treatment effects and power planning.
- Observational methods such as matching/weighting, difference-in-differences, interrupted time series and regression discontinuity when design assumptions are defensible.
- Causal DAGs and explicit confounder assumptions.
- Sensitivity analysis and robustness checks.
- Never claim causality from correlation or a fitted model alone; require design assumptions and surface limitations.

### H. Specialized research extensions
- Survival analysis and event-time models.
- Reliability and quality control / SPC.
- Psychometrics: item analysis, reliability (e.g. Cronbach's alpha), EFA/CFA where supported.
- Survey analysis with weights, strata and clusters.
- Spatial statistics and geospatial analysis.
- Text analytics and topic/sentiment methods.
- Bioinformatics and domain-specific methods as optional, separately governed extensions.

The registry should grow through versioned plugins. Each plugin must pass validation, test fixtures, numerical accuracy checks, security review and license review before being marked supported.

## 4. Analysis plan contract

The agent returns a typed, declarative plan. It must not return executable Python/R/SQL as the authority for execution.

Example:

```yaml
analysis:
  purpose: compare_groups
  dataset: ds_01J...
  dataset_version: 7
  question: "Is average outcome different between treatment and control?"
  design:
    outcome: outcome_score
    group: treatment_group
    unit_of_analysis: participant_id
    paired: false
  method:
    id: independent_t_test
    alternatives:
      - mann_whitney_u
    parameters:
      alternative: two_sided
      confidence_level: 0.95
      variance_assumption: welch
  preprocessing:
    missing_data: report_and_complete_case
  diagnostics:
    - group_distributions
    - sample_sizes
    - outlier_review
    - variance_comparison
  multiplicity:
    correction: none
  outputs:
    - descriptive_table
    - effect_size
    - confidence_interval
    - test_result
    - distribution_plot
    - diagnostic_report
```

The schema should be versioned and validated. User preferences may set defaults (e.g. confidence level, preferred method family, decimal precision, language, chart style), but cannot override method safety, access policies or required assumptions.

## 5. Algorithm execution architecture

- **Algorithm registry:** stable method IDs, semantic version, input/output schema, capability tags, supported designs, assumptions and diagnostics.
- **Execution adapters:** language/library-specific runners isolated behind a language-neutral contract.
- **Worker isolation:** CPU/memory/time quotas, filesystem restrictions, no ambient network access, allowlisted packages, tenant-scoped temporary storage and cancellation.
- **Reproducibility:** dataset immutable version/hash, algorithm and library versions, configuration, random seed, preprocessing graph, environment/container digest and execution timestamp.
- **Numerical validation:** golden datasets, known-answer tests, tolerances, edge cases, missingness and pathological inputs.
- **Resource governance:** estimate cost, cap row/column counts and execution duration, support sampling only when clearly disclosed.
- **Provenance:** persist the plan, approval, execution logs, diagnostics, output manifest and artifact lineage.
- **Human review:** flag low sample sizes, assumption violations, convergence failures, unstable estimates and unsupported designs. Never silently switch methods.

Potential implementation ecosystem (subject to technical and license evaluation): Apache Commons Math or Hipparchus for JVM statistical primitives; Smile for JVM machine learning; Tribuo for ML; and isolated Python scientific stacks (NumPy, SciPy, pandas, statsmodels, scikit-learn, pingouin, lifelines, PyMC) for broader research coverage. Do not add all libraries to the core runtime. Pin versions, isolate incompatible dependencies, review licenses and security, and validate numerical results before enabling a library.

## 6. Results and presentation

Every analysis result should be represented in a canonical, renderer-neutral result model. The presentation layer can render the same result through web, Tenun, Batik or export adapters.

Supported output types should include:
- Summary cards and descriptive-statistics tables.
- Histograms, density plots, box/violin plots, ECDF and Q-Q plots.
- Scatterplots, pair plots, correlation heatmaps and regression diagnostics.
- Bar/stacked/grouped charts with uncertainty intervals.
- Forest plots, coefficient plots and marginal-effects plots.
- ROC and precision-recall curves, confusion matrices and calibration plots.
- Time-series line charts, decomposition panels, forecast fan charts and residual ACF/PACF.
- Kaplan–Meier curves and survival diagnostics.
- PCA score/loadings plots, scree plots and cluster projections.
- Geographic maps, control charts and specialized domain diagrams where applicable.
- Publication-ready tables, LaTeX/HTML/PDF reports, CSV/XLSX exports and a machine-readable result bundle.

Chart recommendations must be method-aware. A p-value alone is not a visualization. Show units, sample sizes, confidence intervals, uncertainty, missing-data counts and relevant caveats. Tenun is a rendering dependency, while Farabi owns chart semantics and the canonical visualization specification.

## 7. Security, privacy and research integrity

- All imported data is tenant/workspace isolated and governed by Numou-derived trusted identity context where adopted.
- Scan uploads, enforce file size/type limits, validate archive expansion, and treat filenames, formulas and embedded content as untrusted.
- Encrypt data at rest/in transit; apply retention, deletion, export and residency policies.
- Prevent arbitrary code execution and package installation from user prompts.
- Redact sensitive columns in logs and agent context; minimize data sent to external model providers.
- Keep raw data, transformed data and results distinct with lineage.
- Record method selection, changes, exclusions, outlier handling, transformations and analyst approvals.
- Distinguish exploratory analysis from confirmatory analysis; disclose multiple testing and post-hoc exploration.
- Provide reproducibility and limitations; do not fabricate citations or claim a study is peer-reviewed.
- For regulated or high-impact decisions, require domain review and appropriate human approval.

## 8. Integration with the Andalus ecosystem

- **Andalus Framework/Runtime:** conversational research planning, specialist statistical agent, tool orchestration, memory of user preferences (with explicit scope/consent), explanations and workflow coordination. Agent proposes; deterministic Farabi services validate and execute.
- **Numou:** identity, tenant/workspace and reusable platform capabilities; Farabi owns dataset and research permissions.
- **Toba:** scalable source/catalog/query access and large-data processing; Farabi analysis workers handle algorithms not natively supported by the source engine.
- **Batik:** interactive research workspace, agent conversation, review and workflow UI.
- **Tenun:** statistical chart rendering via the Flutter presentation layer.
- **Farabi:** import registry, dataset lifecycle, profiling, statistical method registry, plan validation, execution, provenance, report/artifact lifecycle and BI governance.

## 9. Delivery roadmap

1. **R1 — Data onboarding:** CSV/XLSX/JSON import, secure object storage, schema inference, profiling, dataset catalog and lineage.
2. **R2 — Core statistics:** descriptive summaries, distributions, missingness, correlation, t-tests, chi-square, ANOVA, non-parametric alternatives and publication tables.
3. **R3 — Statistical agent:** question clarification, method recommendations, typed analysis plans, assumption checks and user preference profiles.
4. **R4 — Research methods:** regression/GLM, time series, clustering/PCA, power analysis, bootstrap and multiple-testing correction.
5. **R5 — Advanced research:** causal inference, survival, survey, spatial, Bayesian and domain-specific plugin packs.
6. **R6 — Presentation and reproducibility:** Tenun/Batik integration, report exports, immutable analysis artifacts, rerun and comparison.
7. **R7 — Scale and enterprise:** distributed execution, Toba pushdown, workload isolation, governance, audit, observability and benchmarks.

Each release must include method-specific validation, known-answer tests, reproducibility tests, security tests and documented limitations. The existence of an algorithm ID in the registry is not sufficient evidence that the method is statistically sound or production-ready.
