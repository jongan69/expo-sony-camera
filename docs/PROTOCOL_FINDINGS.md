# Protocol findings and design constraints

This document records the protocol behavior that shaped `expo-sony-camera`. It keeps
three kinds of evidence separate:

1. **Sony-documented behavior** from the current Camera Remote Command materials.
2. **Bench observations** made during the original ListingOS camera experiments.
3. **Package behavior** that is present in this repository today.

A result in one category does not automatically prove the other two.

## PTP is a protocol, not a programming language

Sony Camera Remote Command is Sony's extension of the ISO Picture Transfer Protocol
(PTP). The camera and phone exchange little-endian binary containers containing a
length, container type, operation or response code, transaction ID, and parameters.
This package writes those containers in Kotlin on Android and Swift on iOS.

The transport language is binary PTP even though the implementation language differs:

```text
JavaScript host API
  -> Kotlin or Swift controller
  -> protocol adapter
  -> binary command/data/response containers
  -> Sony camera
```

ScalarWebAPI is different. It uses HTTP/JSON commands plus a JPEG stream over the
camera's Wi-Fi network. PTP/IP is different again: it carries PTP over command and event
TCP connections.

## The three protocol lanes

### USB Camera Control PTP2

Implemented on Android and iOS.

The current sequence is:

1. Find a Sony still-image/PTP interface.
2. Open or claim the camera session.
3. Run Sony authentication operations `0x9201`, `0x9202`, then the final `0x9201`
   phase.
4. Prepare still capture properties.
5. Poll `D221` until live view is ready.
6. Read `GetObjectInfo` and then `GetObject` for virtual live-view handle
   `0xFFFFC002`.
7. Extract the JPEG from Sony's object payload and render it in the native view.

Android owns the USB host connection directly. iOS asks ImageCaptureCore to pass PTP
commands to an attached `ICCameraDevice`; iOS does not expose Android's USB attach and
endpoint APIs.

### ScalarWebAPI over Wi-Fi Direct

Implemented on Android only.

The adapter currently:

- probes the known direct-camera addresses `192.168.122.1` and `192.168.0.1`;
- reads `DmsRmtDesc.xml` and requires an advertised ScalarWebAPI service;
- requests `getAvailableApiList` instead of assuming every control exists;
- opens the advertised live-view URL and extracts JPEGs by SOI/EOI markers;
- uses `actHalfPressShutter`, `getEvent`, `actTakePicture`, and
  `cancelHalfPressShutter` only when advertised;
- downloads and validates the JPEG returned by `actTakePicture`;
- exposes coordinate focus only when `setTouchAFPosition` is advertised.

This is not general local-network discovery yet. The app must already be connected to
the camera's Wi-Fi network.

### Camera Control PTP3 over PTP/IP

Not implemented in this package.

Bench work with an a6700 established the intended adapter shape: discovery, a PTP/IP
command connection, a separate event connection, Sony authentication, serialized
transactions, live-view readiness, and virtual-object transfer. That probe is roadmap
evidence, not a package support claim.

## Why the first USB live view froze

Two USB framing details were essential.

### Preserve bytes beyond the current read

A USB bulk read can contain the end of one PTP container and the beginning of the next.
If the reader asks for only a 12-byte PTP header and discards the rest of the USB packet,
it may throw away the following payload or response. The camera and phone then disagree
about which transaction is active.

The Android transport now reads complete USB packets, returns only the requested bytes,
and buffers the remainder for the next PTP read.

### A zero-length packet can be valid

When a transfer is an exact multiple of the USB endpoint's maximum packet size, the
camera may send a zero-length packet (ZLP) to mark the end of that transfer. Treating
that ZLP as an endpoint failure and clearing the halt can discard Sony's following PTP
response.

The Android reader consumes a zero-length packet as framing, keeps the active
transaction, and continues reading. It also refuses to advance to a new PTP command
after an unrecoverable partial read because that would further desynchronize the
camera.

## Live view is a JPEG flipbook

The native view is not receiving a UVC video device. For PTP2, it repeatedly requests a
virtual object. For ScalarWebAPI, it extracts successive JPEG images from a network
stream. The controller currently targets a 100 ms loop interval, but actual FPS depends
on camera processing, transfer size, USB/network latency, decoding, and device load.

Consequences:

- “10 FPS target” is not a guaranteed frame rate.
- low-latency 30/60 FPS video is not implied;
- every live-view claim needs sustained frame count, effective FPS, and largest-gap
  evidence;
- rendering belongs in the native view so large JPEG frames do not cross the
  JavaScript bridge every cycle.

## Safe still-capture ordering

The virtual captured-image handle is `0xFFFFC001`. It must not be read merely because
the shutter command returned.

The implemented PTP2 capture path:

1. presses Sony's half-shutter control;
2. presses the full-shutter control;
3. waits briefly for focus property `D213`, without making autofocus mandatory for
   manual-focus or back-button-focus setups;
4. releases full press and half press in a cleanup path;
5. polls `D215` (`ObjectInMemory`);
6. waits until the ready value is at least `0x8000`;
7. calls `GetObjectInfo(0xFFFFC001)`;
8. calls `GetObject(0xFFFFC001)`;
9. extracts, validates, and persists the JPEG;
10. resumes live view if the user had requested it.

Reading `0xFFFFC001` before readiness was the main cause of capture-time instability in
the original integration. The same readiness rule is also used to notice camera-body
shutter captures during PTP2 live view. The controller polls for a new object roughly
every ten preview frames and emits `onPhotoCaptured` after persistence.

ScalarWebAPI capture follows the API list instead: optional half press, bounded focus
wait, `actTakePicture`, cleanup cancellation, and download of the returned JPEG URL.

## Reconnect behavior

The controller separates two concepts:

- the transport is temporarily gone; and
- the user no longer wants live view.

Camera power-off or detach preserves the live-view request. An explicit stop or
disconnect clears it. Android ScalarWebAPI connection loss is classified separately
from a command error, uses a single-flight reconnect with a short delay, and suppresses
immediate duplicate discovery. Android USB relies on attach/permission broadcasts;
iOS relies on ImageCaptureCore device removal and re-addition.

These paths are still experimental until the full detach, power-cycle, network-loss,
and foreground/background matrix passes on named hardware.

## Capability discovery beats model branches

The A7 III and a6700 demonstrated why the host app must not contain logic like:

```text
if A7 III -> protocol A
if a6700 -> protocol B
```

The same model can expose different services in different camera modes or firmware
versions. A transport candidate should instead state:

- protocol and transport;
- connection mode;
- advertised command categories and features;
- model and firmware as evidence, not routing truth;
- whether the path is merely detected or physically certified.

`SonyCameraCandidate`, `SonyCameraCapabilities`, and `SonyConnectOptions` reserve this
contract in TypeScript. Candidate enumeration and manual override are not wired into
the `0.1.x` native API yet.

## What is feasible

Sony's current Camera Remote Command materials describe control families beyond this
package's initial surface, including camera settings, shutter release, live-view
monitoring, remote display information, file transfer, deletion, movie-related
controls, and other model-specific operations. Therefore the following are technically
reasonable roadmap items when the active camera advertises them:

- exposure, white balance, drive, color, flash, and lens properties;
- focus areas, manual focus, and coordinate focus;
- movie start/stop and recording status;
- media enumeration, background transfer, and deletion;
- overlays such as peaking, zebra, markers, and remote display information;
- additional wired and wireless Sony models.

Each requires a typed capability, per-model command compatibility, serialization,
unsupported behavior, tests, and physical evidence. “Sony documents a command” does not
mean every supported camera implements it.

## What cannot be promised

This module cannot honestly promise:

- universal support for every Sony camera or every firmware version;
- a control the active camera mode does not advertise;
- automatic iOS app launch when a cable is attached;
- UVC/webcam behavior from a PTP-only camera;
- true coordinate focus on the tested A7 III Scalar mode, which did not advertise
  `setTouchAFPosition`;
- network joining without user/OS participation or stored credentials;
- bypassing pairing, license checks, permissions, or camera security;
- indefinite PTP2 availability on newer firmware/models;
- stable movie, media, property, or PTP3 APIs before those adapters are implemented and
  certified.

## Sony documentation and licensing boundary

Sony's current [Camera Remote Command page](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
lists supported models and interfaces, states that only the latest firmware is
supported, and directs developers to the supplied command compatibility materials.
Sony's June 10, 2026 notice also says PTP2 commands may become unusable on some models
from 2027.

This repository does not bundle Sony SDK binaries or proprietary sample code. Before
publishing a commercial product, review the current application requirements,
[license agreement](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/licenseagreement_d/en.html),
per-model compatibility, and any required camera-side licenses. This document is an
engineering boundary, not legal advice.
