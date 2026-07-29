# Production readiness

## Current release status

The package is a `0.x` experimental module. Android direct USB streaming has one physical
proof point; the package as a whole is not yet production-certified across platforms.

## Green

- Android arm64 direct USB UVC/MJPEG negotiation, complete-frame reconstruction, native
  rendering, 1280x720 at approximately 30 FPS, metrics, and clean detach on the named
  Samsung/a6700 matrix.
- Existing Android PTP2 and ScalarWebAPI implementations with bounded diagnostics.
- Existing iOS PTP2 and external-camera source implementations.
- Explicit native views and optional native modules for web-safe imports.

## Yellow

- Android AAR is reproducibly built for four ABIs from vendored patched source, but only
  the arm64 Samsung/a6700 path has physical evidence.
- Android USB preview frame persistence and diagnostics clearing are not API-parity complete.
- iOS UVC source exists but lacks physical a6700 evidence.
- USB audio is discovered but not captured, encoded, synchronized, or muxed.
- The 57-second Android proof does not satisfy the 30-minute soak gate.

## Red for a 1.0 claim

- Physical certification for every advertised Android ABI and supported phone family.
- Physical iOS UVC certification.
- Multi-phone, multi-firmware, lifecycle, thermal, memory, detach, permission, and
  recovery matrix.
- Encoder-safe frame ownership and documented handoff for a consuming livestream stack.
- Real audio capture/synchronization if audio is advertised as a delivered feature.
- Published-package installation and full matrix rerun from the exact release artifact.
- Security review, dependency/SBOM automation, license review, and release rollback drill.

## Release rule

Do not turn source presence, compilation, endpoint discovery, callback cadence, or one
device run into a broader support claim. Promote each row in `SUPPORT_MATRIX.md` only
when its named evidence gate is recorded.
