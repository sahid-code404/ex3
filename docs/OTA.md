# Development OTA

Tag/release: `dev-latest`

Assets:

- `Camera-dev.apk`
- `update.json`

The Development APK is signed with the repository-contained permanent Development signer. This signer exists only to preserve update continuity for test devices; it is not a production security model.

## Baseline client

The first Development APK contains the updater client so an installed baseline can move to later Development builds without uninstalling. The client:

1. fetches bounded `update.json` asynchronously;
2. rejects wrong schema/channel/application ID, downgrades, incompatible minSdk, unsafe filename/size/hash fields, and signer-pin mismatches;
3. downloads only `Camera-dev.apk` to app-private cache using a `.part` transfer file;
4. enforces byte bounds and declared size while streaming;
5. checks SHA-256;
6. parses the downloaded APK and verifies application ID, exact versionCode and the pinned Development signer;
7. promotes only a verified candidate into the `verified` cache directory;
8. re-checks canonical path, size, SHA-256, package, version and signer immediately before installation;
9. uses `FileProvider` and Android's legitimate package installer UI;
10. on API 26+ uses the official unknown-source permission screen when permission is required.

The update UI is nonmodal. No root, accessibility abuse, hidden APIs or shell installer is used.

Phase 0 has no real camera preview. Its automatic Development check starts only after the foundation UI is already rendered and never delays launch. When Phase 2 introduces production preview, the automatic trigger must move behind `FIRST_PREVIEW_FRAME`; update networking before that event is prohibited.

## CI publication

CI publishes only after compile, host tests, lint, native compilation, API-23 verification, APK package verification and signer verification succeed for the same commit. Asset upload order is APK first, metadata second.

The rolling release metadata pins application ID, version, minSdk, APK size/hash and signer certificate digest. Normal Android devices may still require explicit user/system authorization to install an update; Development OTA automates discovery, download and verification but does not pretend third-party silent installation exists.
