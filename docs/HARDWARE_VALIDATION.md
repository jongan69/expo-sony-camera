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

## Current status

The package contains experimental transport code and inherited bench evidence, but no
NPM release should be described as physically certified until this matrix is completed
for the specific platform/camera combinations being advertised.
