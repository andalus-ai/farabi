# Build and Test Baseline

**Status:** Verified by GitHub Actions on the documentation integration branch  
**Verified:** 2026-11-01  
**Repository:** `andalus-ai/farabi`  
**Branch:** `docs/ecosystem-dependency-map`  
**Commit:** `3b34a9e345293dd64d53ac84f07a70dbec71cbb5`

## CI result

The repository's `.github/workflows/ci.yml` runs on pushes to `main`, `feature/**`, `fix/**`, and `docs/**`, and on pull requests. Its `verify` job:

1. Checks out the repository.
2. Installs Eclipse Temurin Java 21 and configures Maven caching.
3. Runs `mvn --batch-mode --no-transfer-progress verify` from the repository root.

The GitHub Actions run [41275552442](https://github.com/andalus-ai/farabi/actions/runs/41275552442) completed with conclusion **success**. The `verify` job and the Maven `verify` step both completed successfully on the commit above.

This is a real reactor-level Maven verification, not a documentation-only workflow. The build uses the root Maven reactor and its five modules: `engine`, `runtime-core`, `adapter-jdbc`, `adapter-dremio`, and `runtime-quarkus`.

## Test inventory observed in the repository

There are 18 Java test classes matching `*Test.java` in the checked branch:

| Module | Test classes |
| --- | ---: |
| `engine` | 15 |
| `adapter-jdbc` | 1 |
| `adapter-dremio` | 2 |
| `runtime-core` | 0 |
| `runtime-quarkus` | 0 |
| **Total** | **18** |

The inventory is based on source paths, not a Surefire test-case count. The successful Maven verification confirms the configured lifecycle completed; it does not, by itself, establish integration coverage against live Numou, Andalus Runtime, Toba, or production identity providers.

## What this baseline proves

- The current root Maven reactor builds and verifies on Java 21 in GitHub Actions.
- The checked-in unit-test suite executed as part of Maven's `verify` lifecycle without causing the workflow to fail.
- The documentation branch's latest commit did not break the build.

## What it does not prove

- Production readiness, security correctness, or complete semantic query coverage.
- Runtime behavior against live external services or data systems.
- End-to-end identity propagation, tenant isolation under adversarial conditions, worker fencing, idempotent remote recovery, or human approval enforcement.
- Adequate tests in `runtime-core` and `runtime-quarkus`; neither currently has a `*Test.java` class in the inspected tree.
- Coverage thresholds, mutation testing, dependency vulnerability status, or performance/load characteristics.

## Recommended next quality gates

See the [Runtime Security Test Plan](RUNTIME_SECURITY_TEST_PLAN.md) for concrete cases and implementation order covering identity resolution, deny-by-default behavior, workspace isolation, and authorization-before-fetch.


1. Add runtime-core composition tests for adapter wiring and lifecycle behavior.
2. Add runtime-quarkus tests for authentication context mapping, deny-by-default behavior, request validation, and workspace isolation.
3. Add contract tests for the Andalus Runtime proposal adapter before enabling a live model/runtime.
4. Add adversarial authorization tests proving that prompt text, model output, and tool arguments cannot override workspace, subject, dataset, fields, or filters.
5. Add durable execution tests for duplicate idempotency keys, timeout-after-remote-acceptance, reconciliation, and worker fencing.
6. Publish Surefire reports and test counts as CI artifacts so future baseline comparisons are measurable.
