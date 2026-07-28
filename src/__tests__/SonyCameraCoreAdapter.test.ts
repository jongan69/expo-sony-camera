import {
  mapSonyCapabilities,
  mapSonyState,
  normalizeSonyError,
} from '../camera-core/SonyCameraCoreAdapter';

describe('Sony Camera Core adapter', () => {
  it('maps native lifecycle states without exposing protocol states', () => {
    expect(mapSonyState('streaming')).toBe('previewing');
    expect(mapSonyState('discovering')).toBe('connecting');
    expect(mapSonyState('reconnecting')).toBe('reconnecting');
    expect(mapSonyState('unsupported')).toBe('error');
  });

  it('does not promote advertised but unimplemented native APIs', () => {
    const capabilities = mapSonyCapabilities(
      {
        protocols: ['sony_camera_control_ptp2'],
        transports: ['usb'],
        connectionModes: ['usb'],
        categories: ['live_view', 'still_capture', 'movie', 'media'],
        features: {
          liveView: true,
          stillCapture: true,
          halfPress: true,
          touchFocus: true,
          properties: true,
          movieRecording: true,
          mediaBrowser: true,
        },
      },
      false
    );

    expect(capabilities.preview.supported).toBe(true);
    expect(capabilities.preview.touchFocus).toBe(false);
    expect(capabilities.stillCapture.supported).toBe(true);
    expect(capabilities.stillCapture.halfPress).toBe(true);
    expect(capabilities.video.supported).toBe(false);
    expect(capabilities.properties.readable).toBe(false);
    expect(capabilities.media.browse).toBe(false);
  });

  it('marks an uncertain capture timeout as retryable with unknown operation state', () => {
    expect(normalizeSonyError(new Error('capture timeout'), 'capture_photo')).toMatchObject({
      code: 'operation_timeout',
      retryable: true,
      operationStateKnown: false,
    });
  });
});
