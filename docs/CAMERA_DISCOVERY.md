# Camera discovery plan

Phase 1 will implement staged evidence rather than a monolithic startup probe.

1. Hot path: load only validated cached route/topology seed needed for first preview.
2. Public Camera2 enumeration: call `CameraManager.getCameraIdList()` and treat every ID as opaque.
3. Bounded characteristics: collect facing, optics, sensor geometry, hardware level, capabilities, stream maps, FPS/exposure/sensitivity ranges, RAW sizes and relevant stabilization/focus metadata with per-family caps.
4. API 28+ logical/physical relations: inspect the logical multi-camera capability and `physicalCameraIds`; record logical route + optional physical member without assuming standalone openability.
5. Optional public Camera NDK metadata on supported API levels, batched across JNI and metadata-only.
6. Runtime validation after a visible main preview: separate ADVERTISED, PREVIEW_VERIFIED, RAW_VERIFIED, TEMPORARILY_FAILED and STRUCTURALLY_UNUSABLE states.
7. Conservative canonical-lens reconciliation using multiple independent optical evidence families. Uncertainty keeps lenses separate.

No manufacturer, model, SoC, sensor brand or numeric camera-ID branch is allowed.
