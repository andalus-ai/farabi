# Dataset import HTTP status API

The Quarkus runtime exposes `GET /api/dataset-imports/{jobId}` for authenticated clients.

## Authorization contract

- The endpoint is protected by Quarkus `@Authenticated`.
- Workspace scope is read only from the authenticated `SecurityIdentity` attribute `workspace_id`.
- The endpoint never accepts a workspace ID from the request.
- Missing or blank workspace scope returns `403`.
- The JDBC store lookup includes both the job ID and authenticated workspace ID. A job belonging to another workspace is indistinguishable from a missing job and returns `404`.

The deployment's OIDC/JWT identity integration must map the trusted workspace claim to the `workspace_id` identity attribute. Do not map an unverified request parameter or user-editable header to this attribute.

## Response

The response includes job and dataset identifiers, state, attempt counters, timestamps and failure details. It does not expose storage locations, source credentials, or file contents.

## Current boundary

This is the first HTTP edge for durable imports, not the multipart upload implementation. Upload submission remains unavailable until the runtime has a trusted workspace/source authorization policy and a request-scoped byte-stream handoff to the intake adapter. The local filesystem adapter remains a single-node development option; production object storage and retention/cleanup are still outstanding.
