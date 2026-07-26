# expo-sony-camera — Sony camera control for Expo and React Native

Build a mobile app that talks to a Sony camera with Expo and React Native. This Expo
native module provides Sony camera control, tethered shooting, remote shutter capture,
native JPEG live view, and captured-image transfer for Android and iOS apps.

It is designed for developers building a Sony Alpha camera app, Sony remote-camera
workflow, mobile product-photography tool, camera monitor, remote shutter, or camera
capture integration without writing the Android Kotlin and iOS Swift bridge from
scratch. The package uses Sony Camera Remote Command-compatible PTP transports and
Sony ScalarWebAPI where the active camera advertises those capabilities.

This package is experimental (`0.x`). It contains native protocol work for:

- Android USB Camera Control PTP2
- Android Sony ScalarWebAPI over Wi-Fi Direct
- iOS wired Camera Control PTP2 through ImageCaptureCore

Tested protocol terminology and camera families include Sony A7 III (`ILCE-7M3`), Sony
a6700 (`ILCE-6700`), Camera Control PTP2, PTP/IP, PTP3, ScalarWebAPI, USB PTP camera,
Wi-Fi Direct camera, Sony Alpha live view, remote shutter, and JPEG capture. PTP3 is
documented as a roadmap adapter, not a supported `0.1.x` transport.

Support is capability-driven. A camera model name alone is not treated as proof of
protocol support, and a successful package build is not hardware certification.

## What works today

| Capability                      | Android USB PTP2              | Android ScalarWebAPI                                     | iOS wired PTP2                       |
| ------------------------------- | ----------------------------- | -------------------------------------------------------- | ------------------------------------ |
| Detect/connect                  | Implemented                   | Implemented for known Wi-Fi Direct addresses             | Implemented through ImageCaptureCore |
| Native JPEG live view           | Implemented                   | Implemented                                              | Implemented                          |
| App-triggered still capture     | Implemented                   | Implemented when `actTakePicture` is advertised          | Implemented                          |
| Transfer captured JPEG          | Implemented                   | Implemented                                              | Implemented                          |
| Camera-body shutter import      | Implemented by polling `D215` | Not implemented                                          | Implemented by polling `D215`        |
| Half-press/focus before capture | Implemented                   | Implemented when advertised                              | Implemented                          |
| Coordinate tap-to-focus         | Not implemented               | Implemented only when `setTouchAFPosition` is advertised | Not implemented                      |
| Automatic reconnect             | Experimental                  | Bounded reconnect implemented                            | Experimental                         |

“Implemented” means the code path exists. It does not mean every camera, firmware,
phone, cable, or network path is certified. See the
[support matrix](./docs/SUPPORT_MATRIX.md) for evidence and limitations.

## Who should use this

This package is for an Expo or React Native developer who needs to:

- connect an Android phone to a Sony camera over USB and Camera Control PTP2;
- use Sony ScalarWebAPI over a camera's Wi-Fi Direct network;
- render a Sony camera live view inside a native mobile app;
- trigger a Sony camera shutter from a phone;
- transfer Sony camera JPEGs into a React Native or Expo photo workflow;
- build a Sony Alpha tethered-shooting, remote-monitor, product-photography, or camera
  control app with a reusable native module.

It is not a UVC webcam wrapper, a browser camera API, or a promise of universal Sony
camera compatibility.

## Installation

```bash
npx expo install expo-sony-camera
```

Then add the package to the app's Expo plugins:

```json
{
  "expo": {
    "plugins": ["expo-sony-camera"]
  }
}
```

Create a development build after installing because this package contains native code.

## Example

The [`example/`](./example) directory is a small Expo app that exercises connection,
live view, and still capture. From the repository root:

```bash
npm install
npm run build
cd example
npm install
npx expo run:android
```

The example is intentionally not a full camera application. It demonstrates the module
surface that another Expo app can build on top of.

## JavaScript API

```tsx
import SonyCamera, { SonyCameraView } from 'expo-sony-camera';

const state = SonyCamera?.getState();
await SonyCamera?.connect();
await SonyCamera?.startLiveView();
const photo = await SonyCamera?.capturePhoto();
await SonyCamera?.stopLiveView();
await SonyCamera?.disconnect();

<SonyCameraView active style={{ flex: 1 }} />;
```

The module emits `onStateChanged`, `onDeviceAttached`, and `onPhotoCaptured` events.
On web, the module is unavailable and the view renders nothing.

`focusAt(x, y, viewWidth, viewHeight)` currently exists only on Android and succeeds
only when the active ScalarWebAPI camera advertises coordinate touch focus. Callers
must handle `unsupported`; the module does not pretend that center-focus is coordinate
focus. `SonyConnectOptions` and the candidate/capability types reserve the future
protocol-selection contract, but candidate enumeration and transport overrides are not
public runtime features in `0.1.x`.

## Documentation

- [Public API](./docs/API.md)
- [Architecture](./docs/ARCHITECTURE.md)
- [Protocol findings and design constraints](./docs/PROTOCOL_FINDINGS.md)
- [Support matrix and evidence levels](./docs/SUPPORT_MATRIX.md)
- [Capability and PTP3 roadmap](./docs/ROADMAP.md)
- [Physical hardware validation](./docs/HARDWARE_VALIDATION.md)
- [Releasing to NPM](./docs/RELEASING.md)

## Camera setup

For USB PTP2, enable the camera's PC Remote mode, return to the normal shooting screen,
and use a data-capable USB connection. Android requires USB host support and camera
permission. iOS requires the host app to be open and ImageCaptureCore to expose PTP
command pass-through for the attached camera.

For ScalarWebAPI, connect the phone to the camera's Wi-Fi Direct network before opening
the example app. The camera must expose the documented ScalarWebAPI descriptor and live
view endpoint. Android discovery uses SSDP multicast (`urn:schemas-sony-com:service:ScalarWebAPI:1`)
and falls back to the fixed Wi-Fi Direct addresses `192.168.122.1` and `192.168.0.1`.

> **Android cleartext traffic:** The library manifest sets `android:usesCleartextTraffic="true"`.
> This is required because Sony camera Wi-Fi networks do not provide HTTPS. The setting is
> scoped to the library; it does not affect other network traffic in the host app.

## Development

```bash
npm install
npm run lint
npm run build
npm test
npm pack --dry-run
```

The Android unit tests cover the PTP container and JPEG codec. Sustained live view,
capture, reconnect, camera power-off, cable removal, and permission recovery require
physical-device tests and are not claimed by the automated suite.

## Scope and limitations

- Wireless PTP3 is not implemented yet.
- iOS wireless transports are not implemented yet.
- Camera property control, movie control, and media browsing are not part of the first
  public API.
- PTP live view is a sequence of JPEG object transfers, not a UVC webcam stream.
- The module cannot add a control that a camera/firmware/mode does not advertise.
- The module does not bypass pairing, network permissions, camera licensing, or Sony's
  per-model command compatibility.
- Sony SDK binaries and proprietary sample code are not bundled.
- Sony announced in Camera Remote Command 2.02.00 that Camera Control PTP2 commands may
  become unavailable on some models from 2027 and are not recommended for new support
  without checking the supplied compatibility materials. See
  [Sony Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html).
- Review Sony's current documentation, model/firmware compatibility, application
  process, and license terms before shipping a product built on this module.

## License

MIT. See [LICENSE](./LICENSE).
