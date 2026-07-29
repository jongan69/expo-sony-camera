# Changelog

## 0.2.1

### Added

- Added direct Android USB UVC/MJPEG preview for the Sony a6700 in USB Streaming mode.
  The Sony-specific libuvc framing fix reconstructs MJPEG frames split across Android
  16 KB bulk reads and publishes only complete JPEGs ending in EOI.
- Added USB stream state, frame cadence, resolution, frame-gap, transport, and USB audio
  device diagnostics.
- Added native USB streaming views for Android and iOS, plus production-readiness,
  hardware-evidence, setup, and third-party provenance documentation.

### Changed

- Vendored the complete patched UVCAndroid source at its exact upstream commit and added
  reproducible build, four-ABI verification, certified-artifact rollback, and SHA-256
  integrity tooling.

## 0.2.0

### Fixed

- Replaced the `SDIO_GetAllExtDevicePropInfo` (`0x9209`) byte scan with a real
  `SDIExtDevicePropInfo` parser on Android and iOS. The previous reader searched the
  payload for a two-byte property code and assumed a fixed offset to the current value,
  so it could match bytes inside an earlier property's enumeration list and return an
  unrelated value. Against a fixture where `D215`'s code appears in a preceding
  enumeration, the old reader returned `4` where the true value was `0x8001` — below the
  `0x8000` capture-ready threshold, which presents as "the camera fired, but no JPEG
  reached the app".
- `connect(options)` no longer throws. The native functions took zero arguments while
  TypeScript declared an optional options object, and Expo validates argument count.
  Overrides are recorded in diagnostics and ignored; candidate selection is still unimplemented.
- Android now routes ScalarWebAPI traffic over the camera's Wi-Fi network via
  `ConnectivityManager.requestNetwork`. A camera Wi-Fi Direct link has no internet, so
  Android kept cellular as the process default and requests could leave the device.
- Android calls `startRecMode` when the camera advertises it, then re-reads the API list.
  Several bodies expose almost no shooting APIs until remote shooting mode is entered, so
  supported cameras could be reported as unsupported.
- ScalarWebAPI requests are routed to the service the descriptor advertises (`camera`,
  `system`, `avContent`, `guide`) instead of being hardcoded to `camera`.
- Focus waits use Sony's long-polling `getEvent` against a deadline instead of a blind
  sleep loop, and report `started` rather than implying focus was confirmed.
- The Android unit test suite referenced `parseLiveViewJpeg`, which does not exist, so it
  had never compiled. CI never ran Gradle, so nothing caught it. Both are fixed.
- Fixed a `DateFormatter` data race in the new iOS diagnostics buffer.
- Capabilities are now derived from the camera's own `GetDeviceInfo` (`0x1001`) response.
  Both controllers previously returned hardcoded feature literals, so the package claimed
  capabilities it had never checked. A camera that does not advertise the underlying
  operation, property, or event codes is now reported as not supporting the feature, and a
  camera that refuses `GetDeviceInfo` reports an empty feature set rather than a guess.
- Large objects transfer via `GetPartialObject` (`0x101B`) in 4 MB windows above an 8 MB
  threshold, when the camera advertises it. `GetObject` needed one contiguous allocation
  the size of the whole file, which fails on mid-range Android devices well before the
  100 MB container ceiling and rejected larger RAW files outright.
- ScalarWebAPI discovery now issues an SSDP `M-SEARCH` for
  `urn:schemas-sony-com:service:ScalarWebAPI:1` before falling back to the two fixed
  Wi-Fi Direct addresses, so cameras on infrastructure networks are discoverable.
- iOS capture no longer queues behind the live-view loop on the same serial queue, where
  `prepareLiveView` could hold it for minutes. Capture now waits for the loop to yield,
  bounded, and fails fast instead.

### Added

- `getDiagnostics()` and `clearDiagnostics()` are implemented on Android and iOS. They
  were declared in TypeScript and implemented on neither. iOS gained a diagnostics buffer.
- `npm run sync-version` propagates `package.json` into the podspec and `build.gradle`,
  and runs from `prepare` and `prepublishOnly`. `sync-version:check` fails CI on drift.
  The publish workflow now bumps, tags, publishes, and pushes only after a successful
  publish.
- CI runs the Kotlin unit tests, `format:check`, and the version-sync check.
- Android USB device filter also matches Sony still-image interfaces that omit the PTP
  subclass and protocol descriptors in PC Remote mode.
- `capturePreviewFrame()` persists the current live-view frame without triggering the
  shutter. Preview frames previously never reached JavaScript at all. It returns a
  preview-resolution still and does not emit `onPhotoCaptured`.

### Changed

- `docs/ROADMAP.md` replaced with an audit of the package against Sony's Camera Control
  PTP 2 and PTP 3 references, and a plan targeting the complete documented command
  surface through a generic protocol engine rather than per-property bindings.

## 0.1.1

- Expanded NPM keywords, package description, GitHub discoverability, and README search
  language for Sony camera, Expo, React Native, PTP, ScalarWebAPI, live view, and remote
  shutter integrations.
- Clarified tested Sony Alpha model terminology and the PTP3 roadmap boundary.
- Documented the exact runtime API, protocol findings, support evidence, architecture,
  hardware-validation process, and capability roadmap.
- Recorded Sony's announced PTP2 compatibility warning and the package's licensing
  boundary.

## 0.1.0

- Initial experimental standalone Expo module.
- Android USB PTP2 and ScalarWebAPI transport extraction.
- iOS wired PTP2 transport extraction.
- Example app for connection, live view, and still capture.
