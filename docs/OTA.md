# Development OTA

Tag/release: `dev-latest`

Assets:

- `Camera-dev.apk`
- `update.json`

The Development APK is signed with the repository-contained permanent Development signer. This signer exists only to preserve update continuity for test devices; it is not a production security model.

CI publishes only after compile, host tests, lint, native compilation, API-23 verification, APK package verification and signer verification succeed for the same commit. Asset upload order is APK first, metadata second.

The future in-app OTA client is required to run only after the first valid preview frame. It will download into app-private storage, verify bounded size, SHA-256, application ID, higher versionCode, minSdk compatibility and the pinned signer before invoking Android's legitimate package-install/update UI. No root, accessibility abuse, hidden APIs or exploit-based silent installation is permitted.
