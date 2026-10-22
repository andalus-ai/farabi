# R6 — Batik / Tenun integration contract

This milestone introduces an explicit, renderer-neutral Java projection of persisted R5 manifests.

## Source projects
- Batik: https://github.com/andalus-ai/batik (Flutter, model-agnostic A2UI; supports custom component registration).
- Tenun: https://pub.dev/documentation/tenun/latest/
- Tenun core: https://pub.dev/documentation/tenun_core/latest/
- Tenun pro: https://pub.dev/documentation/tenun_pro/latest/
- Tenun 3D core: https://pub.dev/documentation/tenun_3d_core/latest/

## Boundary

```text
Farabi Java engine -> RendererEnvelope.Dashboard -> JSON transport -> Batik Flutter custom components -> Tenun widgets
```

The envelope carries a stable chart ID, renderer discriminator, dataset binding, filter metadata, layout and manifest revision. The Java engine does not depend on Flutter or a specific chart library. UI clients must use an explicit allowlist for renderer names, never execute strings from prompts, and fetch chart data through authenticated governed query APIs.

Renderer discriminators are Farabi-owned identifiers, **not verified Tenun API names**. The Flutter adapter must map them to actual supported Tenun constructors and handle unavailable chart families.

## Security
- Render envelopes contain dataset identifiers, not raw SQL or result rows.
- Filters are UI metadata, not enforced authorization. Server-side query policies remain mandatory.
- Workspace-scoped manifest retrieval must precede conversion.
- Do not expose internal datasource credentials or bearer tokens to Flutter clients.

## Remaining integration work
- A Flutter package implementing Batik custom nodes backed by Tenun widgets.
- Validate actual Tenun constructors and compatible versions in Dart integration tests.
- Authenticated HTTP endpoint for the envelope and governed chart data retrieval.
- Real-time streaming updates, empty/error/loading states, theming, accessibility and 3D chart support.
