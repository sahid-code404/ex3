# Camera discovery plan and Phase 1 status

Discovery is a staged evidence system rather than a monolithic startup probe.

1. Hot path: load only validated cached route/topology seed needed for first preview.
2. Public Camera2 enumeration: call `CameraManager.getCameraIdList()` and treat every ID as opaque.
3. Bounded characteristics: collect facing, optics, sensor geometry, hardware level, capabilities, stream maps, FPS/exposure/sensitivity ranges, RAW sizes and relevant stabilization/focus metadata with per-family caps.
4. API 28+ logical/physical relations: inspect the logical multi-camera capability and `physicalCameraIds`; record logical route + optional physical member without assuming standalone openability.
5. Public Camera NDK evidence on supported API levels is gathered as an independent metadata-only source through one batched JNI call.
6. Runtime validation after a visible main preview: separate ADVERTISED, PREVIEW_VERIFIED, RAW_VERIFIED, TEMPORARILY_FAILED and STRUCTURALLY_UNUSABLE states.
7. Conservative canonical-lens reconciliation uses multiple independent optical evidence families. Uncertainty keeps lenses separate.

## Implemented Phase 1 discovery core

- typed transport / physical / route / profile / canonical-lens identities;
- deterministic environment fingerprints;
- conservative topology reconciliation and duplicate-alias tests;
- Camera2 public-ID enumeration;
- bounded, exception-recording CameraCharacteristics collection;
- API-28+ logical/physical relationship extraction;
- API-29+ hidden physical characteristic queries through the public CameraManager contract;
- direct versus logical-default versus logical-physical route separation;
- public Camera NDK enumeration and metadata evidence through `dlopen`/`dlsym`, avoiding a hard API-24 native dependency on API 23;
- Java-vs-NDK enumeration and static-metadata difference diagnostics;
- versioned bounded topology serialization;
- corruption-as-cache-miss behavior;
- app-private topology persistence using temp + fsync + rename;
- bounded discovery diagnostics.

The NDK camera path is evidence-only. It never opens a camera, never loads vendor libraries, never scans `/vendor`, and never becomes a second camera owner.

Runtime preview/RAW trust promotion intentionally belongs to later phases. Physical-device discovery validation is still required before any AUX-success claim.

No manufacturer, model, SoC, sensor brand or numeric camera-ID branch is allowed.
