# Implementation Batch Checklist

Use this template before each comprehensive Farabi ecosystem implementation batch.

## Batch identity
- Batch ID:
- Product milestone (P0–P6):
- User-visible acceptance scenario:
- Repositories and working branches:
- Owning contracts and versions:
- Dependencies/blockers:

## Architecture and security
- [ ] Farabi core remains independent of framework/vendor SDKs.
- [ ] Toba, Batik, Tenun and Numou remain independent products.
- [ ] Trusted principal and tenant context resolved server-side.
- [ ] Dataset, column, filter, action and resource policies enforced.
- [ ] AI plans/A2UI validated as untrusted inputs.
- [ ] Published dashboard revisions remain immutable.
- [ ] Backwards compatibility and migrations addressed.

## Implementation scope
- [ ] Engine/domain contracts
- [ ] Runtime and adapter integration
- [ ] Persistence and migrations where needed
- [ ] UI/agent integration where needed
- [ ] Positive, negative and cross-workspace tests
- [ ] Documentation and Mermaid diagrams updated
- [ ] Observability and error handling

## Verification ledger

| Check | Command or CI link | Result | Evidence |
| --- | --- | --- | --- |
| Compile | | not run | |
| Unit tests | | not run | |
| Integration tests | | not run | |
| Authorization negatives | | not run | |
| End-to-end scenario | | not run | |

## Completion statement

State exactly what was committed, what passed, what remains unverified, and what the next product-level acceptance gap is. **Do not create a PR without explicit user approval.**

See [the implementation plan](IMPLEMENTATION_PLAN.md) and [architecture blueprints](ARCHITECTURE_MERMAID.md).
