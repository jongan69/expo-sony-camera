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

## State and lifecycle

Native controllers serialize camera operations on background executors. Live view is
started and stopped independently from the host screen, and capture temporarily stops
the stream before transferring the captured JPEG. USB attach/detach, foreground entry,
permission responses, transport EOF, and camera power loss feed the same state model.

Native cleanup is expected to be idempotent. A host app should unsubscribe listeners
when a screen unmounts and should stop active live view when leaving the camera surface.

## Platform implementation

### Android

- USB host APIs locate Sony devices exposing a still-image/PTP interface.
- `PendingIntent` permission flow handles camera access.
- USB attachment metadata is installed by `app.plugin.js`.
- ScalarWebAPI discovery currently probes the documented Wi-Fi Direct descriptor
  addresses and parses the advertised action/live-view URLs.
- JPEGs are persisted in the app cache directory and returned as `file://` URIs.

### iOS

- ImageCaptureCore discovers attached camera devices.
- The controller requests PTP pass-through access and performs Camera Control PTP2
  operations through `requestSendPTPCommand`.
- iOS does not receive an Android-style app-owned USB attachment intent; the host app
  must be running when the camera is connected.
- Wireless discovery and PTP/IP are not implemented yet.

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
