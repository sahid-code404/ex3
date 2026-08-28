# Reference research

Research snapshot: 2026-08-28.

## CamX lessons retained

The reference repository `sahid-code404/CamX` was inspected before implementation. Strong patterns retained conceptually:

- a tiny first-install/public seed path separate from deep advertised discovery;
- bounded Camera2 metadata collection with explicit caps;
- opaque camera IDs and separate transport/physical identity;
- physical-camera evidence recorded under a logical parent rather than assumed standalone;
- a single camera-session owner boundary;
- discovery purity and architecture checks in CI;
- permanent Development signing continuity rather than per-build debug keys.

The new project does not copy CamX package structure or make its current implementation a runtime dependency.

## Official Android findings

- `CameraCharacteristics.getPhysicalCameraIds()` is API 28+. A logical camera advertises `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`.
- Prior to API 29, physical IDs returned by a logical camera are also public/openable IDs. Starting API 29, a returned physical ID may be hidden from `CameraManager.getCameraIdList()` and then must be used through the logical camera rather than opened independently.
- Stream combinations involving physical cameras are not universally guaranteed; support must be verified where the API allows it.
- `DngCreator` is available from API 21 and supports `RAW_SENSOR` plus application-generated Bayer-type RAW data.
- Current CameraX exposes RAW capture (`ImageCapture.OUTPUT_FORMAT_RAW`, added in CameraX 1.5.0), but direct Camera2 remains authoritative here because exact RAW capture results, stream control, physical/logical routing and custom burst sequencing are core requirements.
- `PackageInstaller` may require user action for normal third-party installers. Development OTA must automate discovery/download/verification but must not claim silent self-update.

Primary documentation:

- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- https://developer.android.com/reference/android/hardware/camera2/CameraMetadata
- https://developer.android.com/reference/android/hardware/camera2/DngCreator
- https://developer.android.com/reference/androidx/camera/core/ImageCapture
- https://developer.android.com/reference/android/content/pm/PackageInstaller

## Retained / changed / rejected

Retained: capability evidence, bounds, explicit trust states, single ownership, generation safety, hot/deep discovery split.

Changed: this repository starts smaller and phase-gated; no architecture module is created unless it owns a real responsibility or failure boundary.

Rejected: vendor/model/SoC branches, private HAL/vendor libraries, hidden API reflection, camera-ID semantics, and opening physical IDs merely because their strings exist.
