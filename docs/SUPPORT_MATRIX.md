# Support matrix

This matrix distinguishes code presence, bench evidence, and release certification.
Those are separate claims.

| Platform | Camera/protocol                                    | Code status     | Current package claim           | Required certification                                               |
| -------- | -------------------------------------------------- | --------------- | ------------------------------- | -------------------------------------------------------------------- |
| Android  | USB Camera Control PTP2                            | Implemented     | Experimental wired transport    | Sustained live view, repeated capture, detach/reconnect              |
| Android  | ScalarWebAPI over Wi-Fi Direct                     | Implemented     | Experimental wireless transport | Discovery, sustained live view, ordered capture, power-loss recovery |
| Android  | Camera Control PTP3 over PTP/IP                    | Not implemented | Roadmap only                    | See [PTP3 roadmap](./ROADMAP.md)                                     |
| iOS      | Wired Camera Control PTP2 through ImageCaptureCore | Implemented     | Experimental wired transport    | iPhone/cable matrix, permissions, sustained view/capture             |
| iOS      | ScalarWebAPI over Wi-Fi                            | Not implemented | Not supported                   | Local-network discovery and physical validation                      |
| iOS      | Camera Control PTP3 over PTP/IP                    | Not implemented | Roadmap only                    | Native socket lifecycle and parity fixtures                          |
| Web      | Native camera control                              | Fallback only   | Unavailable                     | No native camera claim                                               |

## Inherited bench evidence

The extraction began from ListingOS camera work that observed:

- Sony A7 III (`ILCE-7M3`) smartphone Wi-Fi mode using ScalarWebAPI, including a
  15-second 640×360 live-view soak at approximately 14.95 FPS and successful focus,
  shutter, cancellation, and JPEG download operations.
- Sony a6700 (`ILCE-6700`) exposing authenticated PTP3 over PTP/IP, including command
  and event sessions, live-view readiness, object metadata, and one live-view JPEG.

These observations justify the adapter architecture and roadmap. They do not certify
this NPM package, every firmware version, every phone, or every cable/network mode.

## Evidence levels

1. **Source evidence** — code, types, fixtures, and unit tests exist.
2. **Build evidence** — the package or native example compiles.
3. **Physical evidence** — a named camera, firmware, phone, OS, cable/network path,
   and sustained test result are recorded.
4. **Release evidence** — the version is published and the example is installable from
   the published package.

Do not promote a lower evidence level into a higher support claim.
