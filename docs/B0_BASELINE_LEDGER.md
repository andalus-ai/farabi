# B0 — Baseline Stabilization Ledger

## Goal
Restore reproducible CI before delivering the governed analytics vertical slice described in `docs/IMPLEMENTATION_PLAN.md` (on the separate planning branch until merged).

## Implemented in this branch
- Fixed an unqualified `SCHEMA_VERSION` reference in `ManifestPromotionService`, previously confirmed by GitHub Actions to cause Java compilation failure.
- Expanded Java CI push triggers to `feature/**`, `fix/**`, and `docs/**` so work can be verified without opening many PRs.

## Evidence
- Earlier Java workflow run `40080639571` failed in `farabi-engine` during compilation because `SCHEMA_VERSION` was not resolved.
- The fix explicitly references `DashboardManifests.SCHEMA_VERSION`.
- **Build verification on this branch: pending**. Do not claim success until a completed workflow has been inspected.
- Flutter verification and real Batik/Tenun integration compatibility: pending.

## B0 completion gate
- [ ] Java CI completed successfully on this branch
- [ ] Flutter dashboard package CI completed successfully
- [ ] Flutter Tenun package CI completed successfully
- [ ] Integration contracts inventoried and documented
- [ ] Remaining security gaps recorded with tests and owners

## Next product slice
After B0, prioritize server-side governed dataset filters, semantic query contracts and authorization before data access. Avoid spending the next batch on isolated renderer polish.
