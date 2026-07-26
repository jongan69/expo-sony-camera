# Support matrix

This matrix distinguishes code presence, bench evidence, and release certification.
Those are separate claims. No row is “certified” in `0.1.x`.

## Transport matrix

| Platform | Protocol and path                                  | Package code    | Evidence available                                                | Honest current claim                                |
| -------- | -------------------------------------------------- | --------------- | ----------------------------------------------------------------- | --------------------------------------------------- |
| Android  | USB Camera Control PTP2                            | Implemented     | Module build/unit evidence; original ListingOS hardware debugging | Experimental; full post-fix physical matrix pending |
| Android  | ScalarWebAPI over Wi-Fi Direct                     | Implemented     | Strong inherited A7 III live-view/capture evidence                | Experimental on capability-advertising cameras      |
| Android  | Camera Control PTP3 over PTP/IP                    | Not implemented | One inherited a6700 desktop probe                                 | Roadmap only                                        |
| iOS      | Wired Camera Control PTP2 through ImageCaptureCore | Implemented     | Swift/build evidence; no completed iPhone matrix                  | Experimental source path, not hardware-certified    |
| iOS      | ScalarWebAPI over Wi-Fi                            | Not implemented | Android/desktop behavior can inform fixtures only                 | Unsupported in `0.1.x`                              |
| iOS      | Camera Control PTP3 over PTP/IP                    | Not implemented | Protocol probe can inform fixtures only                           | Roadmap only                                        |
| Web      | Native camera control                              | Fallback only   | Web tests                                                         | Unavailable by design                               |

## Feature matrix

| Feature                       | Android USB PTP2                        | Android ScalarWebAPI                                       | iOS wired PTP2                                  | PTP3 future adapter                  |
| ----------------------------- | --------------------------------------- | ---------------------------------------------------------- | ----------------------------------------------- | ------------------------------------ |
| Automatic discovery           | USB vendor/interface attach             | Two known direct-camera IP probes                          | ImageCaptureCore browser                        | Planned UDP/descriptor discovery     |
| Automatic transport selection | USB first, then Scalar fallback         | Selected when USB is absent and descriptor is valid        | Wired only                                      | Planned capability candidates        |
| Live view                     | `D221`, `0xFFFFC002`, native JPEG view  | Advertised live-view URL, native JPEG view                 | `D221`, `0xFFFFC002`, native JPEG view          | Bench-proven shape, not packaged     |
| App still capture             | Half/full press with bounded focus wait | `actTakePicture` when advertised                           | Half/full press with bounded focus wait         | Planned                              |
| Captured JPEG transfer        | `D215`, ObjectInfo, Object              | Download URL returned by camera                            | `D215`, ObjectInfo, Object                      | Planned                              |
| Camera-body shutter import    | Polled during live view                 | Not implemented                                            | Polled during live view                         | Planned through event/readiness path |
| Half-press autofocus          | Used during capture                     | Used when advertised                                       | Used during capture                             | Planned when advertised              |
| Coordinate tap focus          | Not implemented                         | Android only; requires `setTouchAFPosition`                | Not implemented                                 | Capability-dependent roadmap         |
| Property read/write           | Internal readiness properties only      | API-list discovery only                                    | Internal readiness properties only              | Public property API planned later    |
| Movie recording               | Not implemented                         | Camera may advertise APIs, but module does not expose them | Not implemented                                 | Later roadmap                        |
| Media browser/transfer        | Captured JPEG only                      | Captured JPEG only                                         | Captured JPEG only                              | Later roadmap                        |
| Camera protocol events        | Not consumed                            | `getEvent` used only for bounded focus checks              | Delegate exists but PTP events are not consumed | Required for PTP3                    |
| Persistent diagnostics        | `getDiagnostics()`, state payload, Logcat | `getDiagnostics()`, state payload, Logcat                | `getDiagnostics()` and state payload, in-memory | Cross-platform diagnostics planned   |
| Reconnect                     | Attach flow preserves live-view intent  | Single-flight bounded reconnect                            | Re-add preserves live-view intent               | Required before support claim        |

## Important API qualifications

- `onStateChanged`, `onDeviceAttached`, and `onPhotoCaptured` are module events. They do
  not mean a PTP event channel is implemented.
- The exported candidate, capability, property, and preset types reserve a future
  contract and are not callable native features yet.
- `connect(options)` accepts the connect-option argument on both platforms but ignores
  it; candidate selection and transport overrides are not implemented.
- `focusAt` is Android-only at runtime and returns `unsupported` unless ScalarWebAPI
  advertises coordinate touch focus.
- The native view receives JPEGs directly from the controller; preview frames are not
  emitted through JavaScript.

## Inherited bench evidence

The extraction began from ListingOS camera work that observed:

- Sony A7 III (`ILCE-7M3`, firmware 4.03) smartphone Wi-Fi mode using ScalarWebAPI:
  - 15-second, 640×360 live-view probe with 225 unique frames at approximately
    14.95 FPS and a maximum observed gap of 220.5 ms;
  - successful half-press/focus/capture/release and a 590,329-byte JPEG download;
  - a later ListingOS run of 8,235 frames over 13m49s at approximately 9.92 FPS;
  - camera power-off ended the stream without an app crash, ANR, leaked loop, or retry
    storm, while automatic recovery still required follow-up validation;
  - the tested mode advertised 42 APIs but did not advertise
    `setTouchAFPosition`, so coordinate focus is unsupported on that tested path.
- Sony a6700 (`ILCE-6700`, firmware 2.00) exposing authenticated PTP3 over PTP/IP:
  - TCP 15740 accepted the PTP/IP command handshake;
  - Sony authentication and session operations returned success;
  - `D221` became enabled on the normal shooting screen;
  - `GetObjectInfo` followed by `GetObject` returned one 640×360, 21,603-byte JPEG;
  - the same read returned `0x200F` while the camera remained on its Wi-Fi Direct info
    screen, demonstrating that network reachability is not live-view readiness.

Earlier A7 III USB tests exposed the packet-buffer, zero-length-packet, and premature
captured-object bugs described in [Protocol findings](./PROTOCOL_FINDINGS.md). Those
tests motivated the current fixes, but the complete post-fix USB certification matrix
has not been recorded in this standalone package.

These observations justify the adapter architecture and roadmap. They do not certify
this NPM package, every firmware version, every phone, or every cable/network mode.

## Evidence levels

1. **Source evidence** — code, types, fixtures, and unit tests exist.
2. **Build evidence** — the package or native example compiles.
3. **Physical evidence** — a named camera, firmware, phone, OS, cable/network path,
   and sustained test result are recorded.
4. **Release evidence** — the version is published and the example is installable from
   the published package.

5. **Certified matrix evidence** — the published artifact passes the complete duration,
   capture, failure, recovery, and lifecycle matrix on named hardware.

Do not promote a lower evidence level into a higher support claim.

## External compatibility boundary

Sony's current [Camera Remote Command page](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
is the source of truth for supported models, current firmware, interfaces, and command
compatibility. Sony notes that PTP2 commands may become unusable on some models from 2027. This package must therefore report discovered capabilities and prioritize PTP3;
it must not turn today's PTP2 success into a permanent compatibility promise.
