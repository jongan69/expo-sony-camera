# Sony UVCAndroid fork

This vendored tree is derived from UVCAndroid commit
fcdae5f9a194e2111d57019176b53470a120415b.

## Local modification

libuvccamera/src/main/jni/libuvc/src/stream.c contains the Sony MJPEG framing fix
physically proven with an ILCE-6700 on Android. Android USB Host splits large bulk
payloads into 16,384-byte reads. The modified parser:

- synchronizes MJPEG assembly on JPEG SOI (FF D8);
- discards bytes until a complete frame begins;
- treats subsequent USB reads as continuation bytes while a JPEG is active;
- ignores premature UVC EOF flags for an active split MJPEG frame;
- publishes only when JPEG EOI (FF D9) is present.

The original upstream source, history identity, and Apache-2.0 license are preserved in
this directory. UPSTREAM_COMMIT records the exact base revision.

Use npm run build:uvc-aar from the package root. The script builds every Android ABI,
verifies the result, installs the AAR consumed by the Expo module, and regenerates
android/libs/SHA256SUMS.
