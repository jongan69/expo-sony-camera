# Public API

The additive Camera Core compatibility API is documented in
[CAMERA_CORE_ADAPTER.md](./CAMERA_CORE_ADAPTER.md). Existing direct module APIs
remain available during the compatibility migration.

This document describes the callable API. Some exported TypeScript types reserve future
capability, property, preset, and candidate-selection APIs; type availability does not
mean a corresponding native function is implemented.

## Module availability

```ts
import SonyCamera, { SonyCameraView } from 'expo-sony-camera';

if (!SonyCamera) {
  // Web, Expo Go, or a native build that does not include the module.
}
```

`SonyCamera` is optional because the package uses `requireOptionalNativeModule`. Install
the config plugin and create a new native development or release build; adding the NPM
package to JavaScript alone is not enough.

## Methods

| Method                                 | Return                     | Android      | iOS          | Notes                                                                 |
| -------------------------------------- | -------------------------- | ------------ | ------------ | --------------------------------------------------------------------- |
| `getState()`                           | `SonyCameraState`          | Yes          | Yes          | Synchronous snapshot                                                  |
| `connect(options?)`                    | `Promise<SonyCameraState>` | Yes          | Yes          | `options` is reserved; native candidate overrides are not implemented |
| `disconnect()`                         | `Promise<SonyCameraState>` | Yes          | Yes          | Safe to call during cleanup                                           |
| `startLiveView()`                      | `Promise<SonyCameraState>` | Yes          | Yes          | Requests native JPEG streaming                                        |
| `stopLiveView()`                       | `Promise<SonyCameraState>` | Yes          | Yes          | Stops the live-view request loop                                      |
| `capturePhoto()`                       | `Promise<SonyPhoto>`       | Yes          | Yes          | Captures and caches a JPEG, then returns its URI                      |
| `capturePreviewFrame()`                | `Promise<SonyPhoto>`       | Yes          | Yes          | Saves the current live-view frame; no shutter, preview resolution     |
| `focusAt(x, y, viewWidth, viewHeight)` | `Promise<SonyFocusResult>` | Scalar only  | No           | Succeeds only if the camera advertises coordinate touch focus         |
| `getDiagnostics()`                     | `SonyDiagnosticsSnapshot`  | Yes          | Yes          | Full retained trace; synchronous                                      |
| `clearDiagnostics()`                   | `void`                     | Yes          | Yes          | Clears the retained trace, including Android's persisted copy         |

`connect(options)` accepts the `SonyConnectOptions` argument on both platforms. Candidate
selection and transport overrides are **not implemented**: an override is recorded in the
diagnostics trace and then ignored. It is accepted so that the documented signature does
not throw, not because it selects anything.

## Diagnostics

```ts
const { entries, protocol, transport } = SonyCamera.getDiagnostics();
SonyCamera.clearDiagnostics();
```

`entries` is the full retained trace — operation codes, container sizes, state
transitions, and stream metrics. `protocol` and `transport` identify the active adapter
and are absent when nothing is connected. `SonyCameraState.diagnostics` carries a
truncated tail of the same buffer, so the two do not have to be correlated by hand.

Entries are sanitised where they are written: they record codes, sizes, and timings, never
image bytes, serial numbers, or network credentials. Android persists the buffer across
launches; iOS keeps it in memory for the lifetime of the module.

All coordinates passed to `focusAt` are view-space coordinates. The Android Scalar
adapter normalizes them for Sony's API. Callers must handle `status: 'unsupported'`;
USB half-press autofocus is not equivalent to coordinate touch focus.

## State

`SonyCameraState.state` can be:

```text
unsupported, disconnected, discovering, candidate_found, permission_required,
joining_network, connecting, authenticating, ready, streaming, capturing,
recording, transferring, reconnecting, error
```

Not every state is emitted by every current adapter. The union is the shared lifecycle
contract for current and planned transports. `device` identifies the selected adapter
and may include capabilities. `diagnostics` is an optional bounded list of sanitized
native log entries where the platform provides it.

## Events

| Event              | Payload           | Meaning                                        |
| ------------------ | ----------------- | ---------------------------------------------- |
| `onStateChanged`   | `SonyCameraState` | Native connection or operation state changed   |
| `onDeviceAttached` | `SonyCameraState` | A compatible native device became available    |
| `onPhotoCaptured`  | `SonyPhoto`       | A JPEG was captured and persisted successfully |

```tsx
React.useEffect(() => {
  if (!SonyCamera) return;

  const stateSubscription = SonyCamera.addListener('onStateChanged', setCameraState);
  const photoSubscription = SonyCamera.addListener('onPhotoCaptured', enqueuePhoto);

  return () => {
    stateSubscription.remove();
    photoSubscription.remove();
  };
}, []);
```

These are module lifecycle events, not raw PTP event-channel messages. Camera property
events and object-added event subscription are planned separately.

## Native view

```tsx
<SonyCameraView active={cameraState.state === 'streaming'} style={{ flex: 1 }} />
```

`active` controls whether the native view displays incoming frames. It does not connect
the camera or start the protocol stream by itself. Call `connect()` and
`startLiveView()` separately. On web, the module is unavailable and the view should not
be rendered.

## Captured photo

```ts
type SonyPhoto = {
  uri: string; // file:// URI in the app cache
  width: number;
  height: number;
  fileName: string;
  mimeType: 'image/jpeg';
};
```

Cache files are temporary. A consuming app that needs durable photos must copy the file
into its own persistence workflow before the operating system clears the cache.

## Recommended lifecycle

```tsx
await SonyCamera?.connect();
await SonyCamera?.startLiveView();

// Capture one or more photos. Native code pauses and resumes live view.
const photo = await SonyCamera?.capturePhoto();

await SonyCamera?.stopLiveView();
await SonyCamera?.disconnect();
```

Handle rejected promises and continue listening for state changes. Physical detach,
camera power loss, network loss, permission denial, and unsupported commands are normal
runtime conditions, not impossible programmer errors.
