# Production preview architecture

Phase 2 is built around one Camera2 owner and a texture-based API preview so rounded clipping remains reliable on the API-23 baseline.

## Production API preview

- `CameraSessionController` is the sole owner of `CameraDevice`, `CameraCaptureSession`, requests and preview surfaces. Camera work is serialized on one camera thread and every open/switch intent has a monotonically increasing generation.
- The API-23 path uses classic Camera2 session APIs. API-28+ logical/physical routes use public `OutputConfiguration.setPhysicalCameraId()` only when topology selects an actual physical-member route.
- `CameraPreviewTextureView` owns only presentation/surface plumbing. It never opens a camera. `onSurfaceTextureUpdated` supplies rendered first-frame evidence back to the controller.
- `PreviewStreamSelector` chooses only Camera2 `PRIVATE` outputs and favors sensor-aspect streams with acceptable frame duration and bounded preview workload. Capture resolution is not coupled to preview resolution.
- `PreviewAspectMode` models Sensor, 1:1, 4:3, 16:9 and Full as presentation choices. These ratios change presentation geometry rather than rebuilding the camera session.
- `PreviewGeometryEngine` centralizes relative rotation, dimension swapping, front-preview mirroring and normalized center crop. The pure engine has rear/front tests across 0/90/180/270 display rotations.
- `CameraTraceBuffer` is a fixed-capacity primitive ring containing startup, first-frame, lens-switch and later capture milestones. Formatting is kept off the hot path.

## Hot start and trust

A successful rendered frame is the boundary between advertisement and runtime evidence. After `FIRST_PREVIEW_FRAME`, Camera writes a tiny bounded hot-preview seed containing only the verified route, preview size and orientation/facing geometry. On later launches the seed is accepted only when the build/API/camera-ID environment fingerprint still matches and the exact route + stream are revalidated against current public Camera2 metadata. Invalid seeds are discarded and normal bounded capability selection is used.

Full topology discovery remains after first frame. Runtime `PREVIEW_VERIFIED` trust is merged back into the topology cache only for an exact profile fingerprint or the route that just produced a frame; a later discovery refresh does not silently erase previously verified RAW/preview trust for unchanged profiles.

Development OTA starts only after the first rendered preview and initial topology reconciliation. No OTA network request is on the first-frame critical path.

## Remaining Phase-2 hardware gate

CI can verify compile/lint/tests/APK identity but cannot prove camera behavior. Phase 2 still requires physical-device evidence for first-frame orientation, front mirroring, every exposed lens route, rapid latest-intent switching, lifecycle reopen and preview continuity before it can be called hardware complete.
