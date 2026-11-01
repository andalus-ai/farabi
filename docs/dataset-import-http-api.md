# Dataset import HTTP API

The Quarkus runtime exposes authenticated multipart submission at POST /api/dataset-imports and status lookup at GET /api/dataset-imports/{jobId}.

## Authorization contract

- Both endpoints are protected by Quarkus @Authenticated.
- Workspace scope is read only from the authenticated SecurityIdentity attribute workspace_id.
- Subject scope is read from the authenticated principal.
- The endpoint never accepts workspace or subject IDs from the request.
- Missing or blank identity scope returns 403.
- The JDBC status lookup includes both the job ID and authenticated workspace ID. A job belonging to another workspace is indistinguishable from a missing job and returns 404.

The deployment's OIDC/JWT identity integration must map the trusted workspace claim to the workspace_id identity attribute. Do not map an unverified request parameter or user-editable header to this attribute.

## Multipart submission

POST /api/dataset-imports accepts multipart fields:
- file: required binary upload
- datasetName: required workspace-unique dataset name
- sourceId: required source identifier, which must be authorized by the deployment policy
- format: CSV, TSV, JSON or JSONL
- Idempotency-Key: required request header, scoped to the authenticated workspace

The workspace and subject are derived from the authenticated identity. Neither is accepted from multipart fields. The request-scoped file stream is passed directly to the intake port; the local adapter enforces its configured byte limit and checks the actual byte count against the multipart file size. A successful submission returns 202 Accepted with job and dataset IDs. Repeating an idempotency key in the same workspace returns the existing job.

The default CDI authorization policy denies all dataset registrations. A deployment must provide its own DatasetPorts.Authorizer bean that validates both the authenticated workspace/subject and the requested source. Do not replace this with a request-header or multipart-field trust decision.

## Responses

The status response includes job and dataset identifiers, state, attempt counters, timestamps and failure details. Submission returns only job ID, dataset ID and initial state. Neither endpoint exposes storage locations, source credentials, or file contents.

## Deployment boundaries

The local filesystem adapter remains a single-node development option; production object storage, retention/cleanup, workspace quotas, malware scanning, and upload observability are still outstanding. Configure the HTTP request body limit consistently with the adapter byte limit. The worker remains opt-in until storage and database migrations are deployed.
