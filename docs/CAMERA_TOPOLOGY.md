# Camera topology model

Phase 1 treats Android camera identifiers as opaque routing tokens and separates transport identity from optical identity.

## Typed identities

- `CameraTransportId`: a public Camera2 routing token.
- `PhysicalCameraId`: a physical member identifier learned from public logical-camera metadata.
- `CameraRoute`: direct, logical-default, or logical-plus-physical routing evidence.
- `CameraProfile`: one usable/routable capability profile. A physical optical lens may have multiple profiles.
- `CanonicalLens`: a conservative grouping of profiles believed to represent one optical lens.
- `CameraTopology`: deterministic, versioned topology plus bounded diagnostics.

## Conservative reconciliation

The resolver never uses manufacturer, model, SoC, sensor vendor, or numeric-ID semantics. Direct and logical/physical routes that name the same physical member may reconcile when optical metadata does not contradict. Otherwise duplicate aliases require at least five independent matching optical evidence families. A logical multi-camera default route is intentionally not collapsed into one physical member from static metadata alone because its backing lens may switch dynamically.

Uncertainty keeps lenses separate. False separation is preferred to merging two different optical lenses.

## Cache contract

Topology serialization is deterministic and schema-versioned. A cache entry is accepted only when its environment fingerprint matches and all bounded collection limits validate. Parse errors, schema mismatches, oversized structures, and environment changes are cache misses, never startup crashes.
