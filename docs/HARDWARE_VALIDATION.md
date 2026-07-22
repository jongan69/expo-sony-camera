# Hardware validation

Native camera support needs physical evidence. Desktop compilation and a successful
single-frame probe are not sufficient.

## Required record

For every test, record:

- camera model and firmware
- protocol and connection mode
- phone model, OS version, and app/package version
- cable, adapter, or Wi-Fi Direct/infrastructure setup
- camera settings relevant to PC Remote, JPEG transfer, and live view
- start/end time and sustained duration
- frame count, effective FPS, largest frame gap, and transfer sizes
- capture count and whether every shutter produced exactly one JPEG
- permission, detach, power-loss, network-loss, and foreground/background results
- sanitized diagnostics and the exact build artifact used

Use a release or development-client build with an embedded JavaScript bundle. During a
wired-camera test the phone's USB port belongs to the camera, so a Metro-only debug build
can become unusable. On Android, enable wireless ADB before attaching the camera if live
Logcat is required.

## Reusable result template

```text
Package version / git SHA:
Build artifact:
Date and tester:
Camera / firmware:
Phone / OS:
Protocol / transport / connection mode:
Cable, adapter, or network:
Camera setup and active screen:

Discovery and permission result:
Connection result and elapsed time:
Live-view duration / frames / unique frames:
Effective FPS / largest frame gap:
Capture attempts / successful unique JPEGs:
Physical-shutter imports:
Detach / power cycle / reconnect result:
Background / foreground result:
Focus and advertised-capability result:
Errors, crash, ANR, camera freeze, or retry storm:
Sanitized diagnostic attachment:
Verdict: pass / partial / fail
```

## Minimum release matrix

### Android USB PTP2

- permission grant and denial
- five minutes of live view
- ten captures with no duplicates or missing JPEGs
- cable removal and reinsertion
- camera power-off and restart
- app background/foreground
- return to the phone camera after disconnect

### Android ScalarWebAPI

- Wi-Fi Direct discovery without model-name entry
- five minutes of live view
- ten ordered captures and JPEG downloads
- autofocus/half-press behavior where advertised
- Wi-Fi loss, camera power loss, and bounded reconnect
- unsupported touch focus when `setTouchAFPosition` is absent
- discovery failure outside the two currently probed descriptor addresses

### iOS wired PTP2

- intended Lightning and USB-C paths
- ImageCaptureCore permission behavior
- five minutes of live view
- ten captures and detach/reconnect
- foreground/background recovery

### PTP3, when implemented

- command and event channel stability
- authenticated session setup
- `D221` live-view readiness
- `D215` captured-object readiness
- `GetObjectInfo` before `GetObject`
- busy response recovery
- network loss and camera power loss
- five-minute view and ten ordered captures
- camera returned to the shooting screen before evaluating `D221` readiness

## Current status

The package contains experimental transport code and inherited bench evidence. The
following observations are useful engineering evidence, not a completed package
release matrix:

| Camera path                              | Observed result                                                                                                                                                 | Remaining package proof                                                                         |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| ILCE-7M3 4.03, ScalarWebAPI              | 15-second probe: 225 unique 640x360 frames at 14.95 FPS, 220.5 ms largest gap; capture transferred a 590,329-byte JPEG                                          | Repeat from a tagged package artifact; full loss/reconnect matrix                               |
| ILCE-7M3 4.03, ScalarWebAPI in ListingOS | 8,235 frames over 13m49s at 9.92 FPS; no coordinate touch-focus API advertised                                                                                  | Standalone-module soak and automatic reconnect completion                                       |
| ILCE-7M3, USB PTP2                       | Live view/capture fixes addressed truncated packets, USB packet termination, readiness gating, and safe captured-object transfer                                | Full post-fix duration/capture/reconnect matrix                                                 |
| ILCE-6700 2.00, wireless PTP3 probe      | PTP/IP session and authentication succeeded; after returning to the shooting screen, `D221` became ready and one 21,603-byte 640x360 live-view JPEG transferred | No PTP3 package adapter exists; streaming, capture, events, loss, and reconnect remain unproved |

No NPM release should be described as physically certified until the minimum matrix is
completed for the exact package version, platform, camera, firmware, and connection
mode being advertised. A successful single JPEG, TypeScript build, or native compile is
only a lower evidence level; see [SUPPORT_MATRIX.md](./SUPPORT_MATRIX.md).
