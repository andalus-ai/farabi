# P2 integration validation gate

This integration branch intentionally does **not** claim to contain the changes in PRs #6–#13.

## Verified PR file inventory

| PR | Scope | Files |
|---|---|---:|
| #6 | Dremio HTTP transport | 3 |
| #7 | Governed submission | 3 |
| #8 | Query job persistence | 7 |
| #9 | Query coordinator | 3 |
| #10 | Recovery and audit model | 4 |
| #11 | Leases and audit persistence | 4 |
| #12 | Fenced transitions | 3 |
| #13 | Discovery and backoff | 4 |

## Blocker

GitHub branch lookup for `feature/p2-query-job-lifecycle` returned `No commit found for the ref`. The current branch cannot safely import the dependency chain from named feature branches until the exact PR head commits are resolved.

## Required order

1. Resolve each PR head SHA (not a guessed branch).
2. Apply transport (#6), governed execution (#7), persistence (#8), coordinator (#9), recovery (#10), lease (#11), fencing (#12), discovery (#13) to a single integration branch.
3. Check Java imports and API signatures; the previous milestones were committed separately without compilation.
4. Verify migration paths and V2/V3/V4 ordering.
5. Run `mvn test`, PostgreSQL integration tests, and Dremio loopback tests.
6. Fix failures before marking the integration PR ready.

**No integration merge or tests have been performed.**
