# Architecture

`expo-sony-camera` is an Expo Modules API package. The package owns the native
camera connection lifecycle and exposes a small JavaScript surface. The example
app is a consumer and test harness; it is not part of the published NPM payload.

## Layers

```text
Consumer Expo app
  ├─ SonyCameraModule.ts        typed JS bridge
  ├─ SonyCameraView.tsx         native live-view surface
  └─ app-specific workflow      photo queues, persistence, seller UX

Expo native module
  ├─ Android SonyCameraModule   Expo lifecycle and event bridge
  ├─ Android controller         USB/Wi-Fi discovery and serialized operations
  ├─ Android transports         USB PTP2 and ScalarWebAPI
  ├─ iOS SonyCameraModule       Expo lifecycle and event bridge
  ├─ iOS controller             ImageCaptureCore device lifecycle
  └─ iOS transport              wired PTP2 command exchange
```

## Public boundary

The package exposes:

- `getState()`
- `connect()` and `disconnect()`
- `startLiveView()` and `stopLiveView()`
- `capturePhoto()` returning a local JPEG URI and dimensions
- optional Android `focusAt()` when the active ScalarWebAPI camera advertises it
- `SonyCameraView` for native live-view rendering
- `onStateChanged`, `onDeviceAttached`, and `onPhotoCaptured` events

The package does not own a seller's photo queue, backend, authentication, analytics,
camera preferences, or listing workflow. Those remain in the consuming app.

The TypeScript types also contain candidates, capabilities, properties, presets, and
diagnostic snapshots. These describe the intended protocol-neutral contract; they are
not all callable runtime APIs in `0.1.x`. The implemented surface is documented in
[API.md](./API.md).

## Runtime selection today

```text
connect()
  Android
    ├─ compatible, permitted USB camera attached -> USB Camera Control PTP2
    └─ otherwise -> probe known ScalarWebAPI Wi-Fi Direct descriptor addresses
  iOS
    └─ ImageCaptureCore camera available -> wired Camera Control PTP2
```

This is adapter selection, not model selection. A future discovery API should return
protocol candidates and capabilities, then allow the caller to choose a candidate.
Passing `SonyConnectOptions` does not currently override native selection.

## State and lifecycle

Native controllers serialize camera operations on background executors. Live view is
started and stopped independently from the host screen, and capture temporarily stops
the stream before transferring the captured JPEG. USB attach/detach, foreground entry,
permission responses, transport EOF, and camera power loss feed the same state model.

Native cleanup is expected to be idempotent. A host app should unsubscribe listeners
when a screen unmounts and should stop active live view when leaving the camera surface.

The controllers preserve the user's live-view intent across a supported reconnect. A
transport may stop, reconnect, and resume streaming without the React component having
to issue a second `startLiveView()`. Reconnect is still experimental and must remain
bounded: one retry workflow, no overlapping sessions, and no infinite tight loop.

## Operation flows

### PTP live view

```text
startLiveView
  -> open/authenticate PTP session
  -> enable Sony live-view mode
  -> wait for Sony live-view readiness
  -> request the current live-view object
  -> decode JPEG natively
  -> render into SonyCameraView
  -> repeat while active
```

The view receives native JPEG frames directly. Full JPEG payloads do not cross the
JavaScript bridge, which prevents React rendering and serialization from becoming the
frame-rate bottleneck. This is a JPEG flipbook, not a UVC video/webcam stream.

### PTP still capture

```text
capturePhoto
  -> pause live-view requests
  -> half-press / focus sequence
  -> trigger shutter
  -> wait for captured-object readiness (D215)
  -> GetObjectInfo
  -> GetObject
  -> validate and persist JPEG in app cache
  -> resume live view if it was requested
  -> emit onPhotoCaptured
```

The `GetObjectInfo` step is required before transferring Sony's virtual captured-object
handle. Skipping it or reading the handle before `D215` is ready caused unstable camera
behavior in bench testing.

## Platform implementation

### Android

- USB host APIs locate Sony devices exposing a still-image/PTP interface.
- `PendingIntent` permission flow handles camera access.
- USB attachment metadata is installed by `app.plugin.js`.
- ScalarWebAPI discovery currently probes the documented Wi-Fi Direct descriptor
  addresses and parses the advertised action/live-view URLs.
- JPEGs are persisted in the app cache directory and returned as `file://` URIs.
- Camera commands are serialized; live-view work has a dedicated executor and must not
  overlap capture transactions.
- Diagnostics are kept in a bounded native buffer, included in state payloads, and also
  available in Logcat.

### iOS

- ImageCaptureCore discovers attached camera devices.
- The controller requests PTP pass-through access and performs Camera Control PTP2
  operations through `requestSendPTPCommand`.
- iOS does not receive an Android-style app-owned USB attachment intent; the host app
  must be running when the camera is connected.
- Wireless discovery and PTP/IP are not implemented yet.
- Camera work is serialized on a native queue so ImageCaptureCore callbacks cannot
  create overlapping PTP transactions.
- A public iOS diagnostics snapshot/clear API is not implemented yet.

## Config plugin

The package plugin adds:

- iOS camera and local-network usage descriptions
- Android USB host capability
- Android USB attachment intent handling
- Sony vendor/PTP interface filtering

The plugin is intentionally app-level configuration. It does not add seller-specific
permissions, backend URLs, credentials, or product workflow behavior.

## Protocol adapter rule

Do not select a protocol from a camera model name alone. Discovery must produce a
candidate with a protocol, transport, connection mode, and capabilities. A future PTP3
adapter must implement the same lifecycle contract as the existing adapters; it must
not create a second model-specific JavaScript API.

## Protocol correctness invariants

Every adapter must preserve these rules:

1. Only one command transaction may be in flight per PTP session.
2. USB reads must preserve bytes beyond the current PTP container; one bulk transfer
   can contain the end of one container and the start of the next.
3. USB writes must correctly terminate packets, including a zero-length packet when a
   transfer ends exactly on the endpoint packet boundary.
4. Captured-object transfer must wait for readiness and request object metadata before
   requesting the object body.
5. Capture must pause and later resume live view without creating a second stream loop.
6. Detach, socket EOF, camera power loss, app teardown, and repeated disconnect calls
   must converge on one clean disconnected state.
7. Unsupported capabilities must return an explicit unsupported result. An adapter must
   not silently substitute a different operation.

## Diagnostics boundary

Diagnostics should answer four questions without requiring a debugger:

- Which candidate, protocol, transport, and camera mode were selected?
- Which state transition or protocol operation failed?
- Was the failure a permission, capability, busy, timeout, disconnect, or malformed-data
  condition?
- Did reconnect stop, retry, recover, or exhaust its bounded policy?

Diagnostics must never contain Wi-Fi credentials, app secrets, image bytes, or personal
file paths. The optional TypeScript `getDiagnostics` and `clearDiagnostics` declarations
are reserved today; they should not be documented as cross-platform runtime methods
until both native implementations export them.
