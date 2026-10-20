# Governance and semantic compilation

This milestone introduces a **reference** explicit-grant authorizer and a deliberately narrow semantic compiler. These are pure Java building blocks, not an identity provider or complete SQL semantic layer.

## Grant model
A grant is scoped to workspace, subject, source and dataset. Unknown grants are denied. Identity must originate from an authenticated runtime context, never from user-controlled request fields.

## Semantic grammar
`datasetId:measureId` resolves through trusted, validated dataset metadata. The reference compiler emits `SELECT SUM(column) AS value FROM qualified_table`. It rejects arbitrary SQL, unknown datasets, unrecognized measures, cross-source datasets and request parameters.

**Limitations:** No row-level policy, column masking, joins, dimensions, filters, aggregation diversity, quotas or semantic revision pinning. Do not install these reference components as a production governance system without extending and validating the trust model.

## Dashboard artifacts
A framework-neutral `Draft` contains immutable chart definitions. `DashboardPromotionService` produces a published revision with provenance and scoped reads. A durable repository adapter, revision uniqueness, ACL sharing, catalog revision pinning and refresh scheduling remain pending.

## Quarkus safety
Quarkus continues to register deny-all authorization and an unconfigured compiler by default. It does not enable a public query endpoint.
