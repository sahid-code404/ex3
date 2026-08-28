# Architecture

## Priority

universality > correctness > camera stability > preview continuity > image quality > latency > maintainability > additional features.

## Invariants

1. Camera IDs are opaque routing tokens. No numeric-ID or vendor-name routing.
2. Exactly one future runtime component, `CameraSessionController`, will own `CameraDevice`, `CameraCaptureSession`, requests, outputs and lifecycle.
3. UI and processing layers never own camera devices.
4. Every asynchronous camera operation will carry a generation token; stale generations are rejected.
5. Every queue and RAW buffer pool is bounded with an explicit overflow policy.
6. Java/Kotlin Camera2 remains the API-23 baseline. Optional NDK camera metadata is feature/API gated and never becomes a second camera owner.
7. OTA/network work is forbidden before the first valid preview frame once production preview exists.

## Phase state

Phase 0 establishes build identity, Compose, NDK, CI, permanent Development signing continuity and rolling Development release plumbing. Hardware camera discovery is intentionally not opened from the Phase-0 screen; Phase 1 begins discovery only after research and typed models are committed.
