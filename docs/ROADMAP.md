# Roadmap

This document is both an audit of the current package and the plan to reach the complete
Sony Camera Remote Command feature set.

It replaces the earlier incremental roadmap. The target is now explicit: **every
operation, device property, control, and event that Sony documents in the Camera Control
PTP 2 and PTP 3 references, plus the ScalarWebAPI surface**, exposed through one
capability-gated JavaScript API on Android and iOS.

Reaching that target by hand is not possible. The plan below explains the architecture
that makes it possible, and the order in which it is safe to build.

---

## 1. The size of the target

Counted from the June 2026 Camera Remote Command 2.02.00 reference documents:

| Surface              | Operations | Device properties | Controls | Events |
| -------------------- | ---------- | ----------------- | -------- | ------ |
| Camera Control PTP 2 | 12         | 41                | 29       | 3      |
| Camera Control PTP 3 | 59         | 685               | 70       | 30     |
| ScalarWebAPI v1      | n/a        | n/a               | ~160 methods across `camera`, `system`, `avContent`, `guide` | 1 (`getEvent`) |

PTP 3 alone covers 35 camera models in its compatibility tables, each with a different
subset of those 685 properties.

The package currently uses **9 distinct PTP operations** (`0x1002`, `0x1003`, `0x1008`,
`0x1009`, `0x9201`, `0x9202`, `0x9205`, `0x9207`, `0x9209`), reads **3 device
properties** (`D213`, `D215`, `D221`), writes **2** (`D25A`, `5013`), invokes **2
controls** (`D2C1`, `D2C2`), and consumes **0 events**. Against PTP 3 that is under 2% of
the documented surface.

### Why the obvious approach fails

Writing 685 typed property bindings, times two platforms, times three protocols, is
around 4,000 hand-maintained code paths. It cannot be tested, cannot be reviewed, and
goes stale every time Sony ships a reference revision (three revisions shipped between
Nov 2023 and June 2026).

### The approach this plan takes instead

Sony's protocol is already uniform. `SDIO_GetAllExtDevicePropInfo` (`0x9209`) returns
*every* supported property of the attached body in one self-describing dataset: code,
datatype, get/set flag, enabled flag, default, current value, form flag, and the
enumeration or range. `SDIO_SetExtDevicePropValue` (`0x9205`) writes any of them.
`SDIO_ControlDevice` (`0x9207`) invokes any control.

So the full surface needs roughly **ten** correct code paths, not four thousand:

1. Parse a `SDIExtDevicePropInfo` dataset array correctly.
2. Encode a value for an arbitrary PTP datatype.
3. Dispatch a get, a set, and a control by code.
4. Read the device's own capability lists.
5. Consume the event channel.
6. Transfer an object, in chunks, of any format.

Everything Sony documents then becomes **data**, not code: a registry mapping codes to
human labels, categories, units, and enum value names. The engine is small and testable;
the registry is large and generated.

This is the central architectural decision in this roadmap. Every phase below serves it.

---

## 2. Audit of the current package

### 2.1 What genuinely works

The protocol work that exists is careful, and the hard-won parts should not be rewritten:

- **USB bulk framing** (`android/.../SonyPtpTransport.kt:321-376`) correctly reads whole
  USB packets, buffers the remainder across PTP container boundaries, and treats a
  zero-length packet as framing rather than as an error. Both bugs are subtle and both
  are fixed.
- **Capture ordering** gates the virtual captured-image handle on `D215 >= 0x8000` and
  reads `GetObjectInfo` before `GetObject`. This is the documented-safe sequence, and
  reading it speculatively is what destabilised the original integration.
- **Live-view readiness** gates on `D221` rather than assuming reachability means ready.
- **Capability honesty**: `focusAt` returns `unsupported` instead of silently
  substituting centre focus. The docs consistently separate code presence from bench
  evidence from certification. Keep this discipline.

### 2.2 Defects found

Ordered by severity. File references are to the current tree.

#### P0 — blocks the full feature set

**D1. The property parser is a byte scan, not a parser.**
`SonyPtpTransport.kt:414-431` and `SonyPtpTransport.swift:245-261` search the `0x9209`
payload for a 2-byte code, then assume a fixed offset to the current value. They never
walk the dataset array, never read the form flag, and never advance by the true record
length.

Two consequences: it can false-match on bytes *inside* an earlier property's enumeration
list and return a garbage value, and it can only ever answer "what is one known
property" — it cannot enumerate. Every property, preset, and settings feature is blocked
on replacing this with a real sequential parser.

**D2. `GetDeviceInfo` (`0x1001`) is never called.**
Neither platform asks the camera what it supports. `SonyCameraController.kt:573-614` and
`SonyCameraController.swift:298-316` return **hardcoded string literals** for
`capabilities.features`. The package's stated design principle is capability-driven
selection; the runtime currently asserts capabilities it never checked.

**D3. The USB interrupt endpoint is never claimed.**
`SonyPtpTransport.kt:49-54` scans only for `USB_ENDPOINT_XFER_BULK`. Sony delivers
`SDIE_ObjectAdded` (`0xC201`), `SDIE_DevicePropChanged` (`0xC203`), and 28 further PTP 3
events on the interrupt endpoint. Without it there is no event channel, which is why
`SonyCameraController.kt:322-324` polls for a new object every ten preview frames — a
workaround that costs a full PTP round trip per poll and still misses events.

**D4. No PTP/IP transport exists.** PTP 3 is the strategic path (Sony's June 2026 notice
says PTP 2 commands may become unusable on some models from 2027) and there is currently
no wireless PTP code on either platform.

#### P1 — correctness and reliability

**D5. `connect(options)` will throw.** `SonyCameraModule.ts:14` declares
`connect(options?: SonyConnectOptions)`, but the native `AsyncFunction("connect")` takes
zero arguments on Android (`SonyCameraModule.kt:34`) and iOS
(`SonyCameraModule.swift:34`). Expo validates argument count, so any caller that actually
passes options gets a runtime exception from a documented-as-safe signature.

**D6. Whole objects are read into a single heap allocation.**
`MAX_CONTAINER_BYTES` is 100 MB (`SonyPtpTransport.kt:38`) and the reader allocates the
full payload up front (`readExact`). A 100 MB single allocation will OOM on many Android
devices, and uncompressed RAW from higher-resolution bodies exceeds the cap outright.
`GetPartialObject` (`0x101B`) and `SDIO_GetPartialLargeObject` (`0x9211`) exist precisely
for this and are unused.

**D7. Android never binds its socket to the camera's Wi-Fi network.** The Scalar
transport uses bare `URL.openConnection()`. When the phone joins a camera Wi-Fi with no
internet, Android keeps cellular as the default network and these requests can leave the
device entirely. `ConnectivityManager.requestNetwork` / `bindProcessToNetwork` with
`TRANSPORT_WIFI` and `NET_CAPABILITY_INTERNET` removed is the fix. This is a common,
confusing, hardware-dependent failure.

**D8. `startRecMode` is never called.** `SonyScalarWebApiTransport.kt:85-93` calls only
`getAvailableApiList`. Several Sony bodies expose almost no shooting APIs until
`startRecMode` has been issued, so the adapter can conclude "unsupported" against a
camera that would have worked.

**D9. Only the `camera` Scalar service is reachable.** The endpoint is hardcoded to
`/camera` (`SonyScalarWebApiTransport.kt:231`). `avContent` (media browsing and
download), `system`, and `guide` are unreachable, and the descriptor's per-service URLs
are discarded.

**D10. iOS serialises capture behind live view on one queue.**
`SonyCameraController.swift:113` uses `workQueue.sync` while `streamLoop` occupies the
same serial queue. `prepareLiveView` can run 50 iterations at a 5-second PTP timeout, so
a capture request can block for minutes rather than failing fast.

**D11. Scalar discovery is two hardcoded IPs.** `DIRECT_IP_CANDIDATES` at
`SonyScalarWebApiTransport.kt:24`. No SSDP `M-SEARCH` for
`urn:schemas-sony-com:service:ScalarWebAPI:1`, so infrastructure-mode cameras are
undiscoverable.

**D12. `getEvent` is called in non-blocking mode inside a sleep loop**
(`SonyScalarWebApiTransport.kt:105`, `:206`). Sony's long-poll form exists to avoid
exactly this. There is also no persistent event loop, so camera-side setting changes are
never observed.

#### P2 — surface and hygiene

**D13.** `getDiagnostics` / `clearDiagnostics` are declared in TypeScript
(`SonyCameraModule.ts:20-21`) and implemented on neither platform. Android already keeps
a bounded trace ring buffer — it just is not exported.
**D14.** iOS has no `focusAt`, no Scalar transport, no reconnect, and no diagnostics.
**D15.** Live-view frames never reach JavaScript. There is no way to grab a preview
still, and no `onFrame`-style opt-in.
**D16.** Version drift: `package.json` 0.1.1, `ios/SonyCamera.podspec` 0.1.0,
`android/build.gradle` 0.1.0.
**D17.** Test coverage is one web-fallback assertion and a 39-line Kotlin codec test.
There are no iOS tests and no protocol fixtures.
**D18.** `android/.../sony_camera_device_filter.xml` matches only subclass 1 / protocol 1;
some bodies enumerate differently in PC Remote mode.

---

## 3. Target architecture

Four layers. Each is independently testable, and only the top one is public.

```text
  Layer 4  Public API
    connect / liveView / capture / properties / movie / media
    typed facade for the ~30 hot controls + generic escape hatch

  Layer 3  Capability resolution
    GetDeviceInfo + SDIO_GetExtDeviceInfo + 0x9209 + Scalar API list
    -> what THIS body, in THIS mode, on THIS firmware supports

  Layer 2  Protocol engine (one per transport)
    PTP2/USB . PTP2/IP . PTP3/IP . ScalarWebAPI
    framing . datatype codec . transaction serialisation
    event channel . chunked object transfer

  Layer 1  Command registry (generated data, not code)
    code -> name, category, datatype, get/set, unit, enum labels,
            per-model compatibility
```

### 3.1 Layer 1 — the command registry

A build-time generator reads the Sony reference PDFs and emits a machine-readable
registry: every operation, property, control, and event code with its name, category,
datatype, get/set capability, unit, enumeration labels, and the model compatibility
matrix.

This is what makes "everything in the specs" tractable. The registry supplies *meaning*;
the camera supplies *availability*; the engine supplies *transport*. Adding support for a
Sony reference revision becomes a regeneration, not a sprint.

**Licensing constraint — this shapes the design.** The Sony reference PDFs grant "a
limited license to download and/or print a copy of this document for personal use." A
derived, redistributed data table in an MIT-licensed npm package is not clearly inside
that grant, and this repository already commits to keeping Sony materials unbundled.

Therefore:

- The **generator script ships**; the **generated registry does not**.
- The generator reads the developer's own local copy of the Sony documents, which they
  obtain from Sony under their own license.
- The package ships a small hand-written registry covering only what the documented
  public API needs, written from protocol observation rather than transcribed.
- Numeric codes observed on the wire are facts about a protocol and are recorded as such;
  verbatim descriptive text and Sony's compatibility tables are not redistributed.

Confirm this with counsel before publishing. The plan is structured so that the answer
changes one build step, not the architecture.

### 3.2 Layer 2 — the protocol engine

One engine per transport, all satisfying the same internal contract:

```text
open() . authenticate() . deviceInfo() . listProperties() . getProperty(code)
setProperty(code, value) . control(code, payload) . events() . object(handle, range)
close()
```

Shared, transport-independent, and unit-testable against fixtures:

- **PTP datatype codec** — the full datatype space including arrays and strings. One
  correct implementation serves all 685 properties.
- **`SDIExtDevicePropInfo` parser** — sequential walk honouring the form flag (`0x01`
  range, `0x02` enumeration), variable-length default and current values, and enum
  counts. Replaces D1.
- **Transaction serialiser** — one in-flight transaction per session, monotonic IDs, no
  reuse across reconnects.
- **Chunked object reader** — `GetPartialObject` / `SDIO_GetPartialLargeObject` with a
  bounded window, so RAW and video transfer without a 100 MB allocation. Fixes D6.

### 3.3 Layer 3 — capability resolution

On connect, ask the camera rather than assuming:

1. `GetDeviceInfo` (`0x1001`) → supported operations, properties, events, capture formats.
2. `SDIO_GetExtDeviceInfo` (`0x9202`) → Sony protocol version and vendor property set.
3. `SDIO_GetAllExtDevicePropInfo` (`0x9209`) → live descriptors with `IsEnabled`.
4. ScalarWebAPI: `getAvailableApiList`, after `startRecMode`.

Intersect with the registry to produce the `SonyCameraCapabilities` the TypeScript types
already describe. Fixes D2 and makes the existing types honest.

`IsEnabled` matters and is currently ignored: a property can be supported but greyed out
in the camera's present mode. The public API must distinguish *unsupported by this body*
from *unavailable in this mode* — they need different UI and different error copy.

### 3.4 Layer 4 — the public API

Two tiers over the same engine:

- **Typed facade** for the ~30 controls apps actually use — ISO, aperture, shutter, white
  balance, exposure compensation, focus mode, drive mode, movie start/stop, focus area.
  Generated from the registry so it cannot drift.
- **Generic escape hatch** — `listProperties()`, `getProperty(code)`,
  `setProperty(code, value)`, `sendControl(code, value)`, `subscribeEvents()`. This is
  what makes the remaining 650 properties reachable without 650 bindings, and what lets
  an app use a camera Sony documents but this package has never seen.

Every call returns a typed result with an explicit unsupported path. No call silently
substitutes a different operation.

---

## 4. Phases

PTP 3 is built in parallel with PTP 2 hardening rather than after it, because the shared
engine is the same work and the 2027 deprecation makes PTP 3 the load-bearing transport.

### Phase 0 — Truth and foundations

Make the existing surface honest before adding to it.

- Fix D5 (`connect` argument mismatch), D13 (`getDiagnostics` / `clearDiagnostics`),
  D16 (version drift).
- Replace the byte scan with the real `SDIExtDevicePropInfo` parser (D1) behind the
  existing internal call sites — no public API change yet.
- Add the PTP datatype codec.
- Build the fixture corpus: captured `0x9209` payloads from the A7 III and a6700, PTP
  containers, malformed frames, `DeviceBusy`, zero-length packets, split packets,
  truncated objects, enumeration and range form flags.
- Stand up iOS unit tests (currently zero) and wire both suites into CI.

**Gate:** the parser round-trips every fixture; the published API matches the native API;
`npm test` covers the codec, parser, and framing on both platforms.

### Phase 1 — Capability resolution

- Call `GetDeviceInfo` and `SDIO_GetExtDeviceInfo` on connect; build capabilities from
  the response (D2).
- Add the hand-written registry subset and the PDF-driven generator (§3.1).
- Surface `IsEnabled` as a distinct state from unsupported.
- Implement candidate enumeration and `SonyConnectOptions` for real, or delete the types.

**Gate:** capabilities reported for the A7 III over USB, the A7 III over Scalar, and the
a6700 differ from each other and match the cameras' actual menus. No hardcoded feature
literals remain.

### Phase 2 — PTP/IP transport (PTP 2 and PTP 3)

The largest single piece, and the one the 2027 deprecation clock is on.

- Native UDP/SSDP discovery on Android and iOS.
- PTP/IP init handshake, separate command and event TCP channels, session keep-alive.
- Serialised transactions across both channels; no ID reuse after reconnect.
- Sony authentication (`0x9201` / `0x9202`) over IP, with PTP 3 protocol version
  negotiation.
- Live view gated on `D221`; capture gated on `D215`.
- Bounded backoff for `DeviceBusy`; one controlled reconnect on EOF or network loss.
- iOS local-network permission copy and Bonjour service declarations.

**Gate:** on the a6700 — five-minute sustained live view with recorded frame count,
effective FPS and largest gap; ten ordered captures; event-channel stability; busy
recovery; camera power-loss recovery; network-loss recovery; foreground/background
recovery. Same matrix on Android and iOS.

### Phase 3 — The event channel

- Claim the USB interrupt endpoint (D3) and read PTP events from it.
- Consume events from the PTP/IP event channel.
- Replace the every-ten-frames poll in `SonyCameraController.kt:322-324` with
  `SDIE_ObjectAdded`.
- Surface `SDIE_DevicePropChanged`, `SDIE_AFStatus`, `SDIE_CapturedEvent`,
  `SDIE_FocusPositionResult`, `SDIE_OperationResults`, and
  `SDIE_MovieRecOperationResults` through a typed `onCameraEvent`.
- Replace Scalar's polled `getEvent` with the long-poll form and a persistent loop (D12).

**Gate:** the camera-body shutter produces exactly one `onPhotoCaptured` with no polling;
changing ISO on the camera body updates JavaScript state within one event round trip.

### Phase 4 — Properties and controls, generically

The point at which "complete feature set" becomes true.

- `listProperties()`, `getProperty`, `setProperty`, `sendControl` over the engine.
- Revision-checked mutation: reject a write against a stale descriptor rather than racing
  the camera.
- Generated typed facade for the hot ~30.
- Presets: apply a set, report per-property applied/failed, define rollback semantics.
- Focus: `Focus Area` (`0xD22C`, PTP 2) and `AF Area Position (x,y)` (`0xD2DC`, PTP 3)
  give real coordinate focus over USB — closing the gap where `focusAt` is currently
  Scalar-only (D14). Also manual focus step (`0xD2D7` / `0xD2D8`), focus magnifier
  (`0xD2CB`), and absolute focus position on PTP 3.

**Gate:** every property the camera reports enabled is readable; every writable one is
writable and the change is visible on the camera body; every unsupported code returns a
typed unsupported result rather than throwing.

### Phase 5 — Movie, media, and transfer

- Movie: `Movie Rec Button` (`0xD2C8` hold, `0xF001` toggle), `Movie Recording State`
  (`0xD21D`), recording lifecycle states, `SDIE_MovieRecOperationResults`.
- Media browsing: `GetStorageIDs` / `GetStorageInfo` / `GetNumObjects` /
  `GetObjectHandles` / `GetObjectInfo`, paginated, with thumbnails via `GetThumb`.
  PTP 3 adds `SDIO_GetContentInfoList`, `SDIO_GetContentData`, and `SDIO_DeleteContent`.
  ScalarWebAPI adds the `avContent` service (D9).
- Chunked transfer with progress and cancellation; RAW (`0xB101`), HEIF (`0xB110`), and
  MPO (`0xB301`) alongside JPEG.
- Background transfer queue.

**Gate:** a 60 MB RAW transfers with progress, is cancellable, and does not spike heap
beyond the chunk window on a mid-range Android device.

### Phase 6 — ScalarWebAPI completion and iOS parity

- SSDP discovery (D11), all four services (D9), `startRecMode` (D8), network binding (D7).
- Full Swift Scalar implementation reusing the Android fixture corpus.
- iOS reconnect and diagnostics parity (D14); fix the queue serialisation (D10).

**Gate:** an identical JavaScript flow succeeds on Android and iOS against the A7 III in
Wi-Fi mode, including permission denial and network loss.

### Phase 7 — The long tail

Everything remaining in the PTP 3 reference — FTP job and setting lists,
`SDIO_UploadData`, PTZF control and presets, BaseLook/LUT import, firmware-update
operations, Eframing, OSD image, stream settings, licence info.

These reach the app through the **generic** property, control, and operation API from
Phase 4. They are not hand-bound, are gated on the camera advertising them, and ship
marked *uncertified* — you do not own an FX6, an FR7, or a BRC-AM7, and code that has
never touched its target hardware must not claim support.

**Gate:** each family is reachable generically, returns typed unsupported on bodies that
lack it, and is documented at evidence level 1 (source) in the support matrix — not
promoted higher.

### Phase 8 — Hardening

- Sustained-run soak tests on every transport.
- Memory and thread-leak profiling under repeated connect/disconnect cycles.
- Fuzz the parsers against malformed camera responses.
- 1.0 API freeze.

---

## 5. Hardware validation matrix

Available for validation: **Sony A7 III (`ILCE-7M3`)**, **Sony a6700 (`ILCE-6700`)**, an
**Android phone**, and an **iPhone**.

| Path                       | Body   | Host    | Covers                               | Phase  |
| -------------------------- | ------ | ------- | ------------------------------------ | ------ |
| USB PTP 2                  | A7 III | Android | Framing, capture, events, properties | 0–4    |
| USB PTP 2 via ImageCapture | A7 III | iPhone  | iOS parity, queue behaviour          | 0–4, 6 |
| ScalarWebAPI Wi-Fi Direct  | A7 III | Android | Discovery, media, network binding    | 6      |
| ScalarWebAPI Wi-Fi Direct  | A7 III | iPhone  | Swift Scalar parity                  | 6      |
| PTP/IP PTP 3               | a6700  | Android | The strategic transport              | 2–5    |
| PTP/IP PTP 3               | a6700  | iPhone  | iOS PTP/IP parity                    | 2–5    |
| USB PTP 3                  | a6700  | Android | PTP 3 over the wire we trust         | 2–4    |

Everything outside this matrix — FX/PXW/BRC/MPC bodies, PTZ, FTP, cinema — is
implementable generically but stays at evidence level 1. See
[`SUPPORT_MATRIX.md`](./SUPPORT_MATRIX.md) for the evidence-level definitions; the rule
that a lower level is never promoted to a higher one is unchanged and applies to every
phase above.

---

## 6. Recommended merge order

1. Phase 0 — truth, parser, codec, fixtures, CI.
2. Phase 1 — capability resolution. *(Phases 2 and 3 can start here in parallel.)*
3. Phase 2 — PTP/IP.
4. Phase 3 — event channel.
5. Phase 4 — generic properties and controls. **This is the milestone where the feature
   set becomes complete in the sense that matters.**
6. Phase 5 — movie, media, transfer.
7. Phase 6 — Scalar completion and iOS parity.
8. Phase 7 — long tail.
9. Phase 8 — hardening, 1.0.

Phases 0 and 1 are prerequisites for everything else and should not be shortened. The
parser and the capability layer are what turn 4,000 hypothetical bindings into ten real
ones.

---

## 7. Non-goals and hard boundaries

Unchanged from previous revisions, and reinforced by the wider scope:

- No support inferred from model names; capability evidence only.
- No claim that all cameras support all documented operations. Sony's own compatibility
  tables show 35 PTP 3 models with different subsets.
- No bypassing Sony pairing, camera licences, OS permissions, or network security.
- No bundling of Sony SDK binaries, sample code, or redistributed reference material —
  including generated data derived from it (§3.1).
- No pretending PTP JPEG polling is a UVC webcam, and no 30/60 FPS promises.
- No silent fallback from a requested operation to a different one.
- No automatic joining of protected camera Wi-Fi without OS and user participation.
- No promise that PTP 2 remains available on future firmware. Sony's June 2026 notice
  says otherwise, which is why Phase 2 is early rather than late.
- Code that has never run against its target hardware is documented as uncertified,
  however complete it looks.
