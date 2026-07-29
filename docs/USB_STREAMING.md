# Sony USB streaming

## Purpose

`SonyUsbStreaming` receives the camera's USB Video Class feed directly on the phone.
It is separate from `SonyCamera`, which controls a camera through PTP or ScalarWebAPI.
The Sony a6700 changes its USB interface descriptors with the selected camera mode:
USB Streaming exposes video/audio interfaces; PC Remote exposes still-image/PTP control.

## Sony a6700 setup

1. Set `USB Connection Mode` to `USB Streaming`.
2. Set `USB Power Supply` to `Off` so the phone remains USB host.
3. Return to the shooting screen.
4. Connect the phone directly with a USB-C data cable.
5. Start `SonyUsbStreaming` and wait for `streamReady === true`.
6. The camera should display `Streaming: Output` after frames begin flowing.

## API

```tsx
import { SonyUsbStreaming, SonyUsbStreamingView } from 'expo-sony-camera';

const state = SonyUsbStreaming?.getState();
await SonyUsbStreaming?.startStreaming();

<SonyUsbStreamingView active style={{ width: '100%', aspectRatio: 16 / 9 }} />;

await SonyUsbStreaming?.stopStreaming();
```

Treat `state === 'streaming' && streamReady === true` as Android frame proof. A device
being attached or a stream being negotiated is not enough. Subscribe to `onStateChanged`
for frame metrics and disconnect/error transitions.

## Android implementation

Samsung's Camera2 HAL did not expose the tested a6700 external camera. The module uses
Android USB Host permission and a direct libusb/libuvc backend instead. The vendored AAR
is based on UVCAndroid commit `fcdae5f9a194e2111d57019176b53470a120415b`.

Android clamps bulk reads to 16,384 bytes. The a6700 can begin a large MJPEG payload with
`02 83 FF D8`; the stock parser interpreted each subsequent 16 KB continuation as a new
UVC payload and published truncated frames. The patched parser locks onto JPEG SOI,
treats split reads as continuation data, ignores premature UVC EOF for active MJPEG, and
publishes only after JPEG EOI. This is the behavior in
`android/libs/UVCAndroid-sony-bulk-patched.aar`.

The active binary is reproducibly built from the vendored patched source and contains
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Only `arm64-v8a` is currently eligible
for physical support. The exact arm64 artifact used for certification is retained beside
the active AAR; the additional ABIs require the complete physical matrix before support
can be advertised.

## iOS implementation

iOS 17 or newer can expose an external UVC camera through AVFoundation. The module has
an `AVCaptureSession`, native preview layer, diagnostics, format enumeration, and sampled
JPEG frame capture. This path has not completed a physical iPhone/a6700 run and remains
experimental. Do not infer iOS support from Android results.

## Recorded Android checkpoint: 2026-07-29

| Field | Result |
| ----- | ------ |
| Camera | Sony a6700 / `ILCE-6700` |
| Camera mode | USB Streaming; display reached `Streaming: Output` |
| Host | Samsung `SM-A166U1` |
| OS | Android 16, API 36, build `BP4A.251205.006.A166U1UEU7DZEB` |
| App | Release APK, direct native libusb/libuvc backend |
| Video | `1280x720` MJPEG at approximately 30 FPS |
| Run | 1,725 frames over approximately 57 seconds |
| Cadence | 28.88-30.51 FPS; 69 ms maximum observed frame gap |
| Rendering | Camera image visibly rendered in the native preview |
| Audio | USB audio device detected; audio samples not captured |
| Termination | Android physical detach; resources closed without app crash |

## Deliberate boundaries

- USB audio endpoint discovery is implemented on Android; PCM capture, encoding, muxing,
  and network publication are not.
- Android preview-frame persistence and diagnostics clearing are not implemented in 0.2.0.
- RTMP, SRT, WebRTC, HLS, and platform broadcast publishing are consuming-app concerns;
  the module currently supplies the camera preview and native frame cadence.
- Remote shutter and camera properties are unavailable in USB Streaming mode. Switch to
  PC Remote/PTP or a supported network control transport for those operations.
- A successful 57-second run is physical evidence, not production certification.

## Production certification gate

For each supported phone/ABI and camera firmware combination, record: 30-minute rendered
preview; frame count and effective FPS; maximum gap; foreground/background; screen lock;
five start/stop cycles; five cable detach/reattach cycles; camera power loss; permission
denial/recovery; thermal/memory profile; USB audio capture when advertised; encoder handoff;
and install from the packed NPM artifact. A build, callback count, or detected endpoint
alone does not pass this gate.

## Troubleshooting

`ready` without `streaming`: call `startStreaming()` and keep the native view mounted.

Repeated attach/detach: verify USB Power Supply is Off, reseat the data cable once, avoid
moving the connector, and confirm Android remains USB host.

Black preview with increasing frames: capture native diagnostics and verify the preview
surface is attached; do not classify callback cadence alone as rendered-video success.

PTP error while USB Streaming is selected: expected. The camera is exposing UVC rather
than the still-image PTP interface.
