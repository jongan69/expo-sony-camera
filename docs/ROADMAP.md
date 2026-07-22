# Roadmap

The roadmap is ordered around protocol correctness and repeatable evidence. Features
are not considered complete because a single desktop probe or one JPEG succeeded.

## Product direction

The public goal is one source-neutral flow:

```text
discover -> connect -> inspect capabilities -> live view -> focus/capture -> JPEG URI
```

The consuming app should not branch on `ILCE-7M3`, `ILCE-6700`, or another model name.
The module selects an adapter from discovered protocol evidence and returns explicit
unsupported results for missing capabilities.

Sony's June 2026 Camera Remote Command notice says PTP2 commands may become unavailable
on some models from 2027. PTP2 remains important for currently deployed cameras, but
PTP3 and capability selection are now compatibility work rather than optional polish.

## 0.1.x — stabilize the extracted module

- Keep the working public API limited to connection, live view, still capture,
  Android Scalar touch focus, the native view, and events.
- Reconcile the TypeScript reservation types with runtime reality:
  - either implement candidate enumeration and connect options or mark them explicitly
    experimental;
  - add real native `getDiagnostics`/`clearDiagnostics` on both platforms or remove the
    optional declarations until implemented;
  - expose capability values consistently, including Scalar touch-focus support.
- Add fixture-driven tests for JPEG framing, PTP containers, malformed responses,
  busy responses, zero-length USB packets, partial packets, disconnects, and captured
  object readiness.
- Record physical test evidence in the matrix format from `SUPPORT_MATRIX.md`.
- Keep Sony SDK binaries and proprietary sample code out of the repository.
- Document camera setup per protocol and platform.

**Exit gate:** published package, example app, native modules, and README describe the
same callable API; Android USB, Android Scalar, and iOS wired builds pass.

## 0.2 — certify the existing transports

### Android USB PTP2

- Complete five-minute live view and ten-capture runs on the A7 III.
- Prove app shutter and camera-body shutter each emit exactly one JPEG.
- Record detach/reinsert, camera power-cycle, permission denial/regrant,
  foreground/background, and screen mount/unmount behavior.
- Add counters for frames, average FPS, largest frame gap, retry count, and capture
  transfer latency to a sanitized diagnostics snapshot.

### Android ScalarWebAPI

- Replace fixed-address-only discovery with SSDP/descriptor discovery while retaining
  bounded fallback probes.
- Prove single-flight automatic reconnect after camera power loss and Wi-Fi loss.
- Confirm ordered capture while live view is active and after focus failure/timeout.
- Hide coordinate focus when `setTouchAFPosition` is absent.

### iOS wired PTP2

- Validate current USB-C iPhones and intended Lightning adapter paths.
- Record ImageCaptureCore authorization, discovery, detach/re-add, and background
  behavior.
- Prove the same `D221`, `D215`, ObjectInfo/Object, and physical-shutter behavior as
  Android.
- Add bounded reconnect and diagnostics parity instead of returning a generic error.

**Exit gate:** each advertised platform/camera path has a named physical matrix and a
reproducible artifact. Until then, the package remains experimental.

## 0.3 — iOS ScalarWebAPI parity

- Add local-network and Bonjour service declarations required by actual discovery.
- Discover the Scalar descriptor on the active camera network.
- Implement HTTP/JSON serialization, JPEG stream framing, capture download, and
  capability-gated touch focus in Swift.
- Tear down connections on network change/background and perform one bounded reconnect
  when live view remains requested.
- Reuse sanitized Android fixtures for descriptor, API-list, error, and JPEG parsing.

**Exit gate:** the same host JavaScript flow works on Android and iOS for a physically
validated Scalar camera, including permission denial and network loss.

## 0.4 / PTP3 Phase 1 — freeze the protocol contract

Before implementation:

- Define `SonyCameraCandidate`, `SonyCameraCapabilities`, and the connection state
  transitions independently of PTP2, PTP3, or ScalarWebAPI.
- Capture sanitized, legally retainable fixtures for:
  - SSDP or equivalent device discovery
  - PTP/IP command and event connection setup
  - Sony authentication operations `0x9201`, `0x9202`, and `0x9209`
  - `D221` live-view readiness
  - `D215` captured-object readiness
  - `GetObjectInfo`, `GetObject`, busy responses, malformed frames, and EOF
- Define redaction rules. Fixtures must not include Wi-Fi credentials, serial numbers,
  bearer tokens, or raw user-identifying network data.
- Add the same expected parser/state-transition assertions for Android and iOS.

**Exit gate:** fixtures and contract tests exist before platform-specific PTP3 code is
treated as implementation-ready.

## PTP3 Phase 2 — Android PTP/IP adapter

Implement `PtpIp3Adapter` behind the existing controller contract:

- Discover cameras using native UDP/SSDP on the active local network.
- Parse the camera descriptor and select the PTP3 candidate by protocol evidence.
- Open and authenticate separate command and event TCP sessions.
- Serialize command transactions and event consumption so one transaction cannot race
  another or reuse a transaction ID.
- Implement live view using the camera's live-view object only after `D221` readiness.
- Implement still capture with ordered half-press/focus/shutter/cancel behavior.
- Gate captured-object reads on `D215`, then request object metadata before downloading
  the JPEG. Do not read the virtual captured-image handle speculatively.
- Bound retries. `DeviceBusy` should use bounded backoff without reopening a healthy
  session; EOF, detach, or network loss should trigger one controlled reconnect path.
- Keep socket and JPEG parsing off the main thread and enforce frame-size limits.

**Acceptance gate:** five-minute live view at the agreed frame-rate floor, ten ordered
captures, event-channel stability, busy recovery, camera power-loss recovery, network
loss recovery, and foreground/background recovery on a named a6700 firmware/Android
device matrix.

## PTP3 Phase 3 — iOS PTP/IP adapter

- Add local-network permission copy and native UDP discovery.
- Use `NWConnection` or an equivalent native socket layer for command/event channels.
- Reuse the Android fixture corpus and state-machine expectations.
- Explicitly tear down sockets on backgrounding, network changes, camera removal, and
  controller destruction.
- Match Android behavior for live-view readiness, capture ordering, JPEG transfer, and
  reconnect diagnostics.

**Acceptance gate:** the Android PTP3 matrix passes on the iOS device/camera/network
matrix, including permission denial and foreground/background transitions.

## PTP3 Phase 4 — public capability selection

- Add discovery results to the public module API only after both platform adapters have
  stable candidate contracts.
- Default to automatic selection; keep protocol and transport overrides under advanced
  diagnostics.
- Provide actionable setup errors without requiring users to understand PTP.
- Persist only a sanitized last-camera record in the host app, never passwords or raw
  endpoints.

**Acceptance gate:** a host app can connect to PTP2, ScalarWebAPI, or PTP3 through the
same JavaScript flow without camera-model branches.

## Capability expansion after transport stability

| Capability                         | Technically feasible?    | Current blocker                                 | Required public contract                                     |
| ---------------------------------- | ------------------------ | ----------------------------------------------- | ------------------------------------------------------------ |
| Exposure/white balance/drive/color | Yes, per command matrix  | Property discovery and safe mutation are absent | Typed property, allowed values, revision, unsupported result |
| Touch/focus-area control           | Sometimes                | Operation differs by protocol/model/mode        | Capability-gated `focusAt`, focus status, coordinate mapping |
| Manual focus and lens zoom         | Sometimes                | Lens/body support and ranges vary               | Units/ranges, operation status, timeout/cancel               |
| Movie start/stop                   | Yes on compatible paths  | No recording lifecycle or recovery              | Recording state, start/stop result, media destination        |
| Media browsing/download            | Yes on compatible paths  | Storage/object enumeration absent               | Paginated items, transfer progress, cancellation             |
| Background file transfer           | Newer compatible cameras | Needs event/session concurrency                 | Transfer queue, progress, camera capability                  |
| Camera display/overlays            | Newer compatible cameras | High bandwidth and per-model support            | Overlay frame/metadata channel                               |
| Presets                            | Yes after properties     | Partial application and rollback semantics      | Revision-checked mutation report                             |
| RAW transfer                       | Potentially              | Size, memory, storage, and workflow decisions   | File type, progress, destination, cancellation               |

Only after the transport lifecycle is stable should these become callable APIs:

- camera properties and revision-checked mutations
- movie recording
- media browsing
- touch focus and focus-area capabilities
- additional Sony camera families and firmware-specific capability fixtures

Each capability requires an advertised native capability, a typed result, an unsupported
path, automated tests, and physical evidence before entering the stable API.

## Explicit non-goals and hard boundaries

The roadmap does not include:

- guessing support from model names;
- claiming all cameras support all Camera Remote Command operations;
- bypassing Sony pairing, camera licenses, OS permissions, or network security;
- bundling Sony SDK binaries or redistributing licensed materials outside their terms;
- pretending PTP JPEG polling is a UVC webcam or promising 30/60 FPS;
- silently falling back from coordinate focus to unrelated center focus;
- automatically joining protected camera Wi-Fi without OS/user participation;
- promising PTP2 will remain available on future firmware.

## Recommended merge order

1. API truth and fixture tests.
2. Existing Android/iOS transport certification and diagnostics parity.
3. iOS ScalarWebAPI.
4. Shared candidate discovery/selection contract.
5. Android PTP3 command/event adapter.
6. iOS PTP3 parity.
7. Public property controls.
8. Movie, media, background transfer, and advanced display capabilities.

This order prevents feature growth from hiding transport framing, serialization, or
recovery failures.
