# expo-sony-camera

An Expo native module for discovering compatible Sony cameras, opening live view,
capturing still photos, and transferring JPEGs into an Expo app.

This package is experimental (`0.x`). It contains native protocol work for:

- Android USB Camera Control PTP2
- Android Sony ScalarWebAPI over Wi-Fi Direct
- iOS wired Camera Control PTP2 through ImageCaptureCore

Support is capability-driven. A camera model name alone is not treated as proof of
protocol support, and a successful package build is not hardware certification.

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
import SonyCamera, { SonyCameraView } from "expo-sony-camera";

const state = SonyCamera?.getState();
await SonyCamera?.connect();
await SonyCamera?.startLiveView();
const photo = await SonyCamera?.capturePhoto();
await SonyCamera?.stopLiveView();
await SonyCamera?.disconnect();

<SonyCameraView active style={{ flex: 1 }} />
```

The module emits `onStateChanged`, `onDeviceAttached`, and `onPhotoCaptured` events.
On web, the module is unavailable and the view renders nothing.

## Camera setup

For USB PTP2, enable the camera's PC Remote/still-image transfer mode and use a
data-capable USB connection. Android requires USB host support and camera permission.
iOS requires the app to be open and permitted to control the attached camera.

For ScalarWebAPI, connect the phone to the camera's Wi-Fi Direct network before opening
the example app. The camera must expose the documented ScalarWebAPI descriptor and live
view endpoint.

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
- Sony SDK binaries and proprietary sample code are not bundled.
- Review Sony's current documentation and distribution terms before shipping a product
  built on this module.

## License

MIT. See [LICENSE](./LICENSE).
