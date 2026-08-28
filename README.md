# Camera

Universal capability-driven Android RAW camera project.

- App name: **Camera**
- Application ID: `com.sahidcode404.camera`
- Minimum Android API: **23**
- Camera authority: **Camera2**
- Native hot paths: **C++/NDK**
- UI: **Jetpack Compose**
- Development delivery: **GitHub Actions + rolling GitHub Release OTA**

The engineering priority is: **universality > correctness > camera stability > preview continuity > image quality > latency > maintainability > additional features**.

Development is phase-gated. A green CI run proves build/static/host-test evidence only; camera hardware behavior must be marked separately as physical-device verified.
