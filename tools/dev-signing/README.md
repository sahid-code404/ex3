# Development signer

`camera-dev.jks.b64` is the permanent **Development OTA** signing identity for this repository.

**THIS KEY PROVIDES DEVELOPMENT UPDATE CONTINUITY, NOT PRODUCTION SECURITY.**

It is intentionally repository-contained for zero-setup Development CI. Never use it for a production distribution channel and never regenerate it after the first installable Development APK has been installed on devices.

Alias: `camera-dev`

The password is deliberately development-only and is present in Gradle configuration. This is not a secret-bearing production design.
