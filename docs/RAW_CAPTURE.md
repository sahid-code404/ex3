# Single-frame RAW DNG capture

This milestone adds the first real still-capture path without introducing JPEG/HEIF or computational fusion.

## Contract

- `CameraSessionController` remains the only Camera2 owner.
- A shutter press is accepted only after a rendered preview frame.
- RAW support is resolved from the active route's current public Camera2 characteristics, not from manufacturer, SoC, sensor-vendor or numeric-ID rules.
- The largest advertised `RAW_SENSOR` size for the active route is used for this single-frame milestone.
- RAW is added lazily: startup remains preview-only, and the controller creates a combined preview + RAW session only on the first RAW shutter press. Later RAW presses can reuse that session while the route remains unchanged.
- Logical/physical routing uses `OutputConfiguration.setPhysicalCameraId()` only on API 28+ and only for a topology-selected physical-member route.
- The RAW `Image` is paired with capture metadata by exact sensor timestamp. A mismatch or timeout fails closed; the app does not write a guessed DNG.
- Physical-member DNG creation requires the matching physical `CaptureResult`; logical metadata is not substituted when physical metadata is absent.
- DNG encoding runs off the camera thread. Android 10+ publishes through scoped MediaStore into `DCIM/Camera`; API 23-28 uses the legacy public MediaStore path and requests legacy write permission only on those releases.
- A successfully published DNG promotes only the exact route to `RAW_VERIFIED` in the cached topology.

## Explicitly not in this milestone

There is no burst capture, HDR bracket, frame rejection, temporal denoise, alignment, fusion, super-resolution, tone mapping, JPEG, HEIF or video processing here. Those must build on top of a physically verified single-frame RAW transaction rather than bypass it.

## Hardware gate

CI can verify source, tests, lint and APK construction, but it cannot prove RAW correctness. Physical-device acceptance must verify exact timestamp pairing, valid DNG readability, correct dimensions/CFA metadata, preview recovery, repeated RAW captures, physical AUX routes where exposed, API 23-28 storage behavior, Android 10+ scoped MediaStore publication and failure behavior on non-RAW routes.
