# Camera Core Sony Adapter

The Camera Core adapter is an additive compatibility layer over the existing
`SonyCamera` Expo native module. It implements the vendor-neutral Camera Core
plug-in boundary without changing the current USB PTP2, ScalarWebAPI, or iOS
ImageCaptureCore transports.

## Usage

```ts
import { createSonyCameraCorePlugin } from 'expo-sony-camera';

const plugin = createSonyCameraCorePlugin();
if (await plugin.isAvailable()) {
  const candidates = await plugin.discover();
  const session = await plugin.connect({ candidateId: candidates[0]?.candidateId });
  const capabilities = await session.capabilities();
}
```

The existing default `SonyCamera` export and `SonyCameraView` remain available
during migration.

## Implemented Camera Core surface

| Camera Core behavior | Adapter behavior |
| --- | --- |
| Packaged plug-in descriptor | `camera.sony.remote`, API version 1 |
| Runtime availability | Native module presence |
| Discovery | Returns the currently attached/discovered native device |
| Connect | Delegates to current native automatic selection |
| Disconnect/close | Idempotent session cleanup |
| Capabilities | Conservative mapping from executable native features |
| Preview | Native live-view start/stop; native view remains the renderer |
| Still capture | Returns a local JPEG asset |
| Preview sample | Saves the current preview frame as a non-listing asset |
| Normalized focus | Converts normalized coordinates through the existing focus bridge |
| Diagnostics | Returns the existing redacted native trace |
| Events | Normalized, ordered session/preview/focus/capture/failure events |

## Explicit gaps

The adapter returns `operation_unsupported` rather than promoting advertised
protocol capabilities that do not have a public native bridge:

- Generic property reads and writes
- Preset application
- Movie start and stop
- Camera media browsing and arbitrary media download
- Battery, storage, and temperature health APIs
- Candidate override and transport selection
- Continuous sampled-frame events
- Mid-command `AbortSignal` cancellation
- iOS coordinate focus
- iOS wireless discovery and PTP/IP
- Production PTP3 control and sustained PTP3 capture proof

`discover()` cannot actively start the current native discovery workflow. It
returns a candidate only when `getState()` already contains a device. During the
migration window, callers may invoke `connect()` without a candidate and allow
the existing native controller to perform automatic selection.

## SDK and platform constraints

- Native plug-ins are compile-time packaged. iOS and Android do not load
  downloaded executable camera adapters.
- Adding the package or adapter to JavaScript does not update an installed app;
  a new native build is required.
- Expo Modules API lifecycle hooks remain responsible for controller teardown.
- Continuous preview stays native. JPEG payloads do not continuously cross the
  JavaScript bridge.
- Android USB requires USB host support, manifest configuration, and explicit
  user permission.
- Android ScalarWebAPI requires the phone to be connected to a compatible camera
  network and is subject to local-network and multicast behavior.
- iOS currently uses ImageCaptureCore for wired PTP2. Wireless support requires
  local-network permissions, multicast entitlement or direct-IP fallback, and a
  separately implemented transport.
- Sony Camera Remote Command material and SDK code must not be copied into this
  package without satisfying Sony's license and distribution requirements.
- Plug-in conformance establishes API behavior, not camera-model certification.

## Migration steps

1. Add Camera Core contracts and keep existing exports unchanged.
2. Register `createSonyCameraCorePlugin()` in the consuming app's compile-time
   Camera Core registry.
3. Run the adapter in shadow mode while the existing direct Sony facade remains
   authoritative.
4. Compare state, capabilities, preview, capture assets, focus results, and
   diagnostics on the same hardware/build.
5. Move acquisition orchestration to Camera Core behind `CAMERA_CORE_ENABLED`.
6. Move product UI branches from Sony model/protocol checks to runtime
   capabilities.
7. Certify Android and iOS physical-device paths independently.
8. Keep the direct Sony facade for one supported release window.
9. Remove the direct path only after telemetry, rollback, and certification gates
   pass.

## Rollback

Disable `CAMERA_CORE_ENABLED` in the consuming application and restore the
existing direct `SonyCamera` facade. The adapter adds no backend schema, media
format, native protocol, or listing-pipeline migration.

## Exit criteria

- Product code imports no Sony protocol or model types.
- Phone and Sony capture enter the same acquisition contract.
- Unsupported operations are explicit and never faked.
- One Camera Core operation produces at most one committed asset.
- Preview and controller resources close deterministically.
- Adapter unit tests and Camera Core conformance tests pass.
- A7 III and a6700 claims remain tied to build, firmware, transport, platform,
  and physical evidence.
