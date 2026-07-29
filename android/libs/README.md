# Vendored Android UVC backend

`UVCAndroid-sony-bulk-patched.aar` is based on UVCAndroid commit
`fcdae5f9a194e2111d57019176b53470a120415b` and contains a Sony MJPEG bulk-transfer
framing fix described in `docs/USB_STREAMING.md`.

The active package artifact contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
The separately retained certified baseline contains `arm64-v8a` only.

`UVCAndroid-sony-bulk-patched-arm64-certified.aar` is retained because it is the exact
artifact physically proven with the Sony a6700 and Samsung `SM-A166U1` on 2026-07-29.
`UVCAndroid-sony-bulk-patched.aar` is rebuilt from the vendored source and is the artifact
consumed by the module. `SHA256SUMS` protects both artifacts. The multi-ABI artifact still
requires the physical matrix before the additional ABIs become certified.

See the repository `THIRD_PARTY_NOTICES.md` before redistribution.
