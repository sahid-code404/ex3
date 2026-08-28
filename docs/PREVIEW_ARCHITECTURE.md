# Production preview architecture

Phase 2 is built around one Camera2 owner and a texture-based API preview so rounded clipping remains reliable on the API-23 baseline.

## Preview policy now committed

- `PreviewStreamSelector` chooses only Camera2 `PRIVATE` outputs and favors sensor-aspect streams with acceptable frame duration and bounded preview workload. Capture resolution is not coupled to preview resolution.
- `PreviewAspectMode` models Sensor, 1:1, 4:3, 16:9 and Full as presentation choices. These ratios are intended to change center-crop/presentation first rather than rebuild the Camera2 session.
- `PreviewGeometryEngine` centralizes relative rotation, dimension swapping, front-preview mirroring and normalized center crop. The engine is pure Kotlin and has rear/front tests across 0/90/180/270 display rotations.
- `CameraTraceBuffer` is a fixed-capacity primitive ring containing startup, first-frame, lens-switch and later capture milestones. Formatting is kept off the hot path.

## Next wiring step

`CameraSessionController` becomes the only owner of `CameraDevice`, `CameraCaptureSession`, requests and preview surfaces. The API-23 path uses the classic Camera2 session APIs. API-28+ logical/physical routing may attach a public physical ID to an `OutputConfiguration` before session creation when the selected verified route requires it. No second Camera2 or CameraX owner is permitted.

The TextureView adapter will translate the pure geometry plan into an Android `Matrix`, detect `onSurfaceTextureUpdated` as actual first-frame evidence, and apply front mirroring only to presentation. Full discovery and OTA are then moved strictly behind `FIRST_PREVIEW_FRAME`.
