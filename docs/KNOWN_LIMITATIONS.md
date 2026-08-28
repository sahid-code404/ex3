# Known limitations

- Phase 0 does not claim camera hardware success. No AUX, RAW, preview or lens-switch behavior is marked physical-device verified yet.
- API 23-27 public Android APIs do not expose the API-28 logical/physical camera relationship API.
- Android camera HALs vary; zero-frame-gap lens switching cannot be promised when hardware requires a device close/open transition.
- Normal third-party Android apps cannot universally perform silent self-update; user/system authorization may be required.
- Real RAW preview is only possible on profiles whose RAW stream configuration and measured cadence can sustain it; processed preview must never be mislabeled RAW.
