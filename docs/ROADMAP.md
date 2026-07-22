# Roadmap

The roadmap is ordered around protocol correctness and repeatable evidence. Features
are not considered complete because a single desktop probe or one JPEG succeeded.

## 0.1.x — stabilize the extracted module

- Keep the public API limited to connection, live view, still capture, and events.
- Add fixture-driven tests for JPEG framing, PTP containers, malformed responses,
  busy responses, and disconnects.
- Record physical test evidence in the matrix format from `SUPPORT_MATRIX.md`.
- Keep Sony SDK binaries and proprietary sample code out of the repository.
- Document camera setup per protocol and platform.

## PTP3 Phase 1 — freeze the protocol contract

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

## Later capabilities

Only after the transport lifecycle is stable:

- camera properties and revision-checked mutations
- movie recording
- media browsing
- touch focus and focus-area capabilities
- additional Sony camera families and firmware-specific capability fixtures

Each capability requires an advertised native capability, a typed result, an unsupported
path, automated tests, and physical evidence before entering the stable API.
