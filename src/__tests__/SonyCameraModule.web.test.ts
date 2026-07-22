import SonyCameraModule from '../SonyCameraModule.web';

describe('SonyCamera web fallback', () => {
  it('does not expose native camera controls on web', () => {
    expect(SonyCameraModule).toBeNull();
  });
});
