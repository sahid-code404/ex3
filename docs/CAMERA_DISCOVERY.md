# Camera discovery plan and Phase 1 status

Discovery is a staged evidence system rather than a monolithic startup probe.

1. Hot path: load only validated cached route/topology seed needed for first preview.
2. Public Camera2 enumeration: call `CameraManager.getCameraIdList()` and treat every ID as opaque.
3. Bounded characteristics: collect facing, optics, sensor geometry, hardware level, capabilities, stream maps, FPS/exposure/sensitivity ranges, RAW sizes and relevant stabilization/focus metadata with per-family caps.
4. API 28+ logical/physical relations: inspect the logical multi-camera capability and `physicalCameraIds`; record logical route + optional physical member without assuming standalone openability.
5. Optional public Camera NDK metadata on supported API levels, batched across JNI and metadata-only.
6. Runtime validation after a visible main preview: separate ADVERTISED, PREVIEW_VERIFIED, RAW_VERIFIED, TEMPORARILY_FAILED and STRUCTURALLY_UNUSABLE states.
7. Conservative canonical-lens reconciliation using multiple independent optical evidence families. Uncertainty keeps lenses separate.

## Implemented in Phase 1 so far

- typed transport / physical / route / profile / canonical-lens identities;
- deterministic environment fingerprints;
- conservative topology reconciliation and duplicate-alias tests;
- Camera2 public-ID enumeration;
- bounded, exception-recording CameraCharacteristics collection;
- API-28+ logical/physical relationship extraction;
- API-29+ hidden physical characteristic queries through the public CameraManager contract;
- direct versus logical-default versus logical-physical route separation;
- versioned bounded topology serialization;
- corruption-as-cache-miss behavior;
- app-private topology persistence using temp + fsync + rename;
- bounded discovery diagnostics.

Still required before Phase 1 is complete: optional public Camera NDK metadata evidence and physical-device validation. Runtime preview/RAW trust promotion intentionally belongs to later phases.

No manufacturer, model, SoC, sensor brand or numeric camera-ID branch is allowed.
