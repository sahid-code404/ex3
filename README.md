# Camera

Universal capability-driven Android RAW camera project.

- App name: **Camera**
- Android package / application ID: `com.sahidcode404.universalcamera`
- Internal Kotlin namespace: `com.sahidcode404.camera`
- Minimum Android API: **23**
- Camera authority: **Camera2**
- Native hot paths: **C++/NDK**
- UI: **Jetpack Compose**
- Development delivery: **GitHub Actions + rolling GitHub Release OTA**

The engineering priority is: **universality > correctness > camera stability > preview continuity > image quality > latency > maintainability > additional features**.

Development is phase-gated. A green CI run proves build/static/host-test evidence only; camera hardware behavior must be marked separately as physical-device verified.

> Identity migration note: the installed Android package was changed from `com.sahidcode404.camera` to `com.sahidcode404.universalcamera`. Existing installs of the old package cannot receive an in-place OTA to the new package identity; install the new package once, then Development OTA continuity continues under the new ID.
